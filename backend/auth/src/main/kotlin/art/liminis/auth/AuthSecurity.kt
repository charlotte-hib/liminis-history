package art.liminis.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.security.authentication.ott.JdbcOneTimeTokenService
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.ott.OneTimeTokenLoginConfigurer
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.jackson.SecurityJacksonModules
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
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

/**
 * Two filter chains. The authorization server's endpoints (authorize, token, revoke, JWKS, OpenID discovery and
 * logout) give curate a code only after a passkey. Everything else is the sign-in itself: the passkey page, the
 * one-time link that lets the curator in once to register a passkey, and the page that registers it.
 */
@Configuration
class AuthSecurity {

    @Bean
    @Order(1)
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
                authorize("/oauth2/authorize", hasAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY))
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

    @Bean
    @Order(2)
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

    /** The curator: no password, only passkeys and, to register the first one, a one-time link. */
    @Bean
    fun users(): UserDetailsService = InMemoryUserDetailsManager(User.withUsername(CURATOR).password("").build())

    /** Client secrets are kept hashed. */
    @Bean
    fun passwordEncoder() = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun registeredClients(jdbc: JdbcOperations): RegisteredClientRepository = JdbcRegisteredClientRepository(jdbc)

    /**
     * Each authorization keeps who signed in as JSON, read back by type name from an allowlist. Spring Security leaves
     * passkey sign-ins out of it, so a refresh after one would fail: added here.
     */
    @Bean
    fun authorizations(jdbc: JdbcOperations, clients: RegisteredClientRepository): JdbcOAuth2AuthorizationService {
        val passkeys = WebauthnJacksonModule()
        val types = BasicPolymorphicTypeValidator.builder().also(passkeys::configurePolymorphicTypeValidator)
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

    /** Every token signed with ES256; access tokens are for the workbench, whichever client asked for them. */
    @Bean
    fun tokens() = OAuth2TokenCustomizer<JwtEncodingContext> { context ->
        context.jwsHeader.algorithm(SignatureAlgorithm.ES256)
        if (context.tokenType == OAuth2TokenType.ACCESS_TOKEN) context.claims.audience(listOf(AUDIENCE))
    }

    /** Only requests addressed to this computer: a page that rebinds its own name to 127.0.0.1 still sends that name. */
    @Bean
    fun firewall() = StrictHttpFirewall().apply { setAllowedHostnames { it == "localhost" || it == "127.0.0.1" } }

    /** A request the firewall turns away gets a 400, not a 500. */
    @Bean
    fun requestRejectedHandler(): RequestRejectedHandler = HttpStatusRequestRejectedHandler()
}
