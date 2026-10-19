package art.liminis.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.security.authentication.ott.JdbcOneTimeTokenService
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.authorization.RequiredFactor
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.ott.OneTimeTokenLoginConfigurer
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.jackson.SecurityJacksonModules
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.security.provisioning.JdbcUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler
import org.springframework.security.web.authentication.ui.DefaultResourcesFilter
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import java.net.URI
import java.time.Duration
import java.util.function.Supplier
import javax.sql.DataSource

/**
 * Three filter chains. The authorization server's endpoints (authorize, token, revoke, JWKS, OpenID discovery and
 * logout) give curate a code only after a passkey. The accounts API takes the admin's access tokens only. Everything
 * else is the sign-in itself: the passkey page, the one-time link that lets a curator in once to register a passkey,
 * and the page that registers it.
 */
@Configuration
class AuthSecurity {

    /** First, as in Spring Boot's own setup: it only takes the authorization server's endpoints. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun authorizationServer(http: HttpSecurity): SecurityFilterChain {
        http.oauth2AuthorizationServer { server ->
            http.securityMatcher(server.endpointsMatcher)
            server.oidc { oidc ->
                // ID tokens are signed with ES256 too: clients check the algorithm against this list.
                oidc.providerConfigurationEndpoint { endpoint ->
                    endpoint.providerConfigurationCustomizer { configuration ->
                        configuration.idTokenSigningAlgorithms { it.clear(); it += "ES256" }
                    }
                }
            }
        }
        http {
            authorizeHttpRequests {
                // The one-time link signs in with FACTOR_OTT only: enough to register a passkey, never for a code.
                authorize("/oauth2/authorize", RecentPasskey)
                authorize(anyRequest, authenticated)
            }
            // The OpenID userinfo endpoint reads the access token.
            oauth2ResourceServer { jwt { } }
        }
        val signIn = LoginUrlAuthenticationEntryPoint("/login")
        http.exceptionHandling {
            it.defaultAuthenticationEntryPointFor(signIn, MediaTypeRequestMatcher(MediaType.TEXT_HTML).apply { setIgnoredMediaTypes(setOf(MediaType.ALL)) })
            it.defaultDeniedHandlerForMissingAuthority(signIn, FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
        }
        return http.build()
    }

    /**
     * The accounts API (AccountController): access tokens auth signed for itself, the audience the admin's carry, with
     * the scope `admin`. No sessions, no cookies, so no CSRF to guard against.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    fun accountsApi(http: HttpSecurity, keys: SigningKeys, settings: AuthorizationServerSettings): SecurityFilterChain {
        val tokens = NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithm(SignatureAlgorithm.ES256).build().apply {
            setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(settings.issuer!!), JwtAudienceValidator(ACCOUNTS_AUDIENCE)))
        }
        http {
            securityMatcher("/api/**")
            authorizeHttpRequests { authorize(anyRequest, hasAuthority("SCOPE_${Scopes.ADMIN}")) }
            oauth2ResourceServer { jwt { jwtDecoder = tokens } }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
        }
        return http.build()
    }

    /** Everything else, at the order of Spring Boot's own default chain. */
    @Bean
    @Order(SecurityFilterProperties.BASIC_AUTH_ORDER)
    fun signIn(http: HttpSecurity, settings: AuthorizationServerSettings): SecurityFilterChain {
        val issuer = URI(settings.issuer!!)
        http {
            authorizeHttpRequests {
                authorize("/", permitAll)
                authorize("/login", permitAll)
                authorize(HttpMethod.GET, "/login/ott", permitAll)
                authorize("/auth.css", permitAll)
                authorize(anyRequest, authenticated)
            }
            webAuthn {
                rpName = "Liminis"
                rpId = issuer.host
                allowedOrigins = setOf(issuer.toString())
                // Ours, in curate's look (AuthPages).
                disableDefaultRegistrationPage = true
            }
            exceptionHandling { authenticationEntryPoint = LoginUrlAuthenticationEntryPoint("/login") }
        }
        // Spring Security's passkey script, which it serves only with its own pages.
        http.addFilter(DefaultResourcesFilter.webauthn())
        http.oneTimeTokenLogin { ott: OneTimeTokenLoginConfigurer<HttpSecurity> ->
            // Our own pages (AuthPages): the default sign-in page would also offer to send a link.
            ott.loginPage("/login")
            ott.showDefaultSubmitPage(false)
            // Or it would take the sign-in page's address too.
            ott.loginProcessingUrl("/login/ott")
            // Links are made by auth itself on start (FirstSignIn), never on request.
            ott.generateRequestResolver { null }
            ott.tokenGenerationSuccessHandler(OneTimeTokenGenerationSuccessHandler { _, _, _ -> error("Links are only made on start") })
            ott.authenticationSuccessHandler(SimpleUrlAuthenticationSuccessHandler("/webauthn/register"))
        }
        return http.build()
    }

    /**
     * The accounts (Accounts), in Spring Security's own tables: no password, only passkeys and, to register the first
     * one, a one-time link.
     */
    @Bean
    fun users(dataSource: DataSource) = JdbcUserDetailsManager(dataSource)

    /** Client secrets are kept hashed. */
    @Bean
    fun passwordEncoder() = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun registeredClients(jdbc: JdbcOperations): RegisteredClientRepository = JdbcRegisteredClientRepository(jdbc)

    /**
     * Each authorization keeps who signed in as JSON, read back by type name from an allowlist. Spring Security leaves
     * passkey sign-ins out of it, so a refresh after one would fail: added here. So is the type of an access token's
     * `auth_time`, a number of seconds.
     */
    @Bean
    fun authorizations(jdbc: JdbcOperations, clients: RegisteredClientRepository): JdbcOAuth2AuthorizationService {
        val passkeys = WebauthnJacksonModule()
        val types = BasicPolymorphicTypeValidator.builder().also(passkeys::configurePolymorphicTypeValidator).allowIfSubType(java.lang.Long::class.java)
        val json = JsonMapper.builder().addModules(SecurityJacksonModules.getModules(javaClass.classLoader, types)).addModule(passkeys).build()
        return JdbcOAuth2AuthorizationService(jdbc, clients).apply {
            setAuthorizationRowMapper(JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(clients, json))
            setAuthorizationParametersMapper(JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(json))
        }
    }

    @Bean
    fun passkeyUsers(jdbc: JdbcOperations) = JdbcPublicKeyCredentialUserEntityRepository(jdbc)

    @Bean
    fun passkeys(jdbc: JdbcOperations) = JdbcUserCredentialRepository(jdbc)

    @Bean
    fun oneTimeTokens(jdbc: JdbcOperations): OneTimeTokenService = JdbcOneTimeTokenService(jdbc)

    /** Tokens signed with the newest of the keys, which all stay published until they expire. */
    @Bean
    fun jwtEncoder(keys: SigningKeys) = NimbusJwtEncoder(keys).apply { setJwkSelector(keys::newest) }

    /**
     * Every token signed with ES256; access tokens are for the workbench, whichever client asked for them. curate's say
     * when the person last used their passkey (`auth_time`, in seconds), for the workbench to ask for a recent one
     * before a publish (a refresh keeps the sign-in's time), and who they are (`name`). Their scopes are those curate
     * asked for that the account's role allows, read again at every refresh, so a revoked account gets no more tokens;
     * the admin's are for auth's accounts API too. wb's tokens have no passkey and no account.
     */
    @Bean
    fun tokens(users: UserDetailsService, accounts: Accounts) = OAuth2TokenCustomizer<JwtEncodingContext> { context ->
        context.jwsHeader.algorithm(SignatureAlgorithm.ES256)
        if (context.tokenType != OAuth2TokenType.ACCESS_TOKEN) return@OAuth2TokenCustomizer
        context.claims.audience(listOf(AUDIENCE))
        if (context.authorizationGrantType == AuthorizationGrantType.CLIENT_CREDENTIALS) return@OAuth2TokenCustomizer
        val principal = context.getPrincipal<Authentication>()!!
        val user = runCatching { users.loadUserByUsername(principal.name) }.getOrNull()
        val role = user?.takeIf { it.isEnabled }?.let { Role.of(it.authorities) }
            ?: throw OAuth2AuthenticationException(OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "${principal.name} may no longer sign in", null))
        val scopes = context.authorizedScopes.intersect(role.scopes)
        context.claims.claim(OAuth2ParameterNames.SCOPE, scopes)
        if (Scopes.ADMIN in scopes) context.claims.audience(listOf(AUDIENCE, ACCOUNTS_AUDIENCE))
        context.claims.claim(NAME, accounts.find(principal.name).name)
        principal.authorities.filterIsInstance<FactorGrantedAuthority>()
            .firstOrNull { it.authority == FactorGrantedAuthority.WEBAUTHN_AUTHORITY }
            ?.let { context.claims.claim(AUTH_TIME, it.issuedAt.epochSecond) }
    }

    /**
     * A code only after a passkey, and, when the request asks for one with OpenID Connect's `max_age` (curate does,
     * before a publish), after a passkey used within that many seconds: an older one sends the curator to sign in
     * again, and the request goes on once they have.
     */
    private object RecentPasskey : AuthorizationManager<RequestAuthorizationContext> {
        override fun authorize(authentication: Supplier<out Authentication?>, context: RequestAuthorizationContext): AuthorizationResult {
            val maxAge = context.request.getParameter("max_age")?.toLongOrNull()
            val passkey = RequiredFactor.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
                // Never under a minute: the request comes back right after the passkey, which must then pass.
                .apply { maxAge?.let { validDuration(Duration.ofSeconds(it).coerceAtLeast(Duration.ofMinutes(1))) } }
                .build()
            return AllRequiredFactorsAuthorizationManager.builder<RequestAuthorizationContext>().requireFactor(passkey).build()
                .authorize(authentication, context)
        }
    }

    /**
     * Only requests addressed to auth's own names: its issuer's, and those it answers to on this computer or on the
     * server's Compose network. A page that rebinds its own name to 127.0.0.1 still sends that name.
     */
    @Bean
    fun firewall(properties: AuthProperties, settings: AuthorizationServerSettings): StrictHttpFirewall {
        val names = properties.allowedHosts + URI(settings.issuer!!).host
        return StrictHttpFirewall().apply { setAllowedHostnames { it in names } }
    }

    /** A request the firewall turns away gets a 400, not a 500. */
    @Bean
    fun requestRejectedHandler(): RequestRejectedHandler = HttpStatusRequestRejectedHandler()
}
