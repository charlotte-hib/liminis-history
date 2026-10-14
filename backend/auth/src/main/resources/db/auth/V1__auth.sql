-- auth's tables, in schema auth of the workbench's Postgres. The first five are Spring Security's own schemas, as its
-- jars ship them, with blob as text and timestamp as timestamptz for Postgres, as their comments ask.

-- The OAuth clients: curate and wb, registered by auth on start (Clients).
create table oauth2_registered_client (
    id varchar(100) not null primary key,
    client_id varchar(100) not null,
    client_id_issued_at timestamptz default current_timestamp not null,
    client_secret varchar(200) default null,
    client_secret_expires_at timestamptz default null,
    client_name varchar(200) not null,
    client_authentication_methods varchar(1000) not null,
    authorization_grant_types varchar(1000) not null,
    redirect_uris varchar(1000) default null,
    post_logout_redirect_uris varchar(1000) default null,
    scopes varchar(1000) not null,
    client_settings varchar(2000) not null,
    token_settings varchar(2000) not null
);

-- Every sign-in and every token issued, with its refresh token: what a refresh and a revocation look up.
create table oauth2_authorization (
    id varchar(100) not null primary key,
    registered_client_id varchar(100) not null,
    principal_name varchar(200) not null,
    authorization_grant_type varchar(100) not null,
    authorized_scopes varchar(1000) default null,
    attributes text default null,
    state varchar(500) default null,
    authorization_code_value text default null,
    authorization_code_issued_at timestamptz default null,
    authorization_code_expires_at timestamptz default null,
    authorization_code_metadata text default null,
    access_token_value text default null,
    access_token_issued_at timestamptz default null,
    access_token_expires_at timestamptz default null,
    access_token_metadata text default null,
    access_token_type varchar(100) default null,
    access_token_scopes varchar(1000) default null,
    oidc_id_token_value text default null,
    oidc_id_token_issued_at timestamptz default null,
    oidc_id_token_expires_at timestamptz default null,
    oidc_id_token_metadata text default null,
    refresh_token_value text default null,
    refresh_token_issued_at timestamptz default null,
    refresh_token_expires_at timestamptz default null,
    refresh_token_metadata text default null,
    user_code_value text default null,
    user_code_issued_at timestamptz default null,
    user_code_expires_at timestamptz default null,
    user_code_metadata text default null,
    device_code_value text default null,
    device_code_issued_at timestamptz default null,
    device_code_expires_at timestamptz default null,
    device_code_metadata text default null
);

-- Passkeys: who they belong to, and their public keys. The private keys never leave the curator's devices.
create table user_entities (
    id varchar(1000) not null primary key,
    name varchar(100) not null,
    display_name varchar(200)
);

create table user_credentials (
    credential_id varchar(1000) not null primary key,
    user_entity_user_id varchar(1000) not null,
    public_key bytea not null,
    signature_count bigint,
    uv_initialized boolean,
    backup_eligible boolean not null,
    authenticator_transports varchar(1000),
    public_key_credential_type varchar(100),
    backup_state boolean not null,
    attestation_object bytea,
    attestation_client_data_json bytea,
    created timestamp,
    last_used timestamp,
    label varchar(1000) not null
);

-- The one-time sign-in links auth prints, to register a passkey (FirstSignIn).
create table one_time_tokens (
    token_value varchar(36) not null primary key,
    username varchar(50) not null,
    expires_at timestamp not null
);

-- The keys that sign the tokens, as JSON Web Keys with their private part (SigningKeys). The newest signs; the older
-- ones stay published for a while, so that the tokens they signed still pass.
create table signing_key (
    id text not null primary key,
    jwk text not null,
    created_at timestamptz not null default now()
);
