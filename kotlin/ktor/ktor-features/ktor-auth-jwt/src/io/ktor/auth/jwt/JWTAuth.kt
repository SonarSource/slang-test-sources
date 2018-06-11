package io.ktor.auth.jwt

import com.auth0.jwk.*
import com.auth0.jwt.*
import com.auth0.jwt.algorithms.*
import com.auth0.jwt.exceptions.*
import com.auth0.jwt.impl.*
import com.auth0.jwt.interfaces.*
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.request.*
import io.ktor.response.*
import java.security.interfaces.*
import java.util.*

private val JWTAuthKey: Any = "JWTAuth"

class JWTCredential(val payload: Payload) : Credential
class JWTPrincipal(val payload: Payload) : Principal

class JWTAuthenticationProvider(name: String?) : AuthenticationProvider(name) {
    internal var authenticationFunction: suspend ApplicationCall.(JWTCredential) -> Principal? = { null }

    internal var schemes = JWTAuthSchemes("Bearer")
    var realm: String = "Ktor Server"
    internal var verifier: ((HttpAuthHeader?) -> JWTVerifier?) = { null }

    /**
     * @param [defaultScheme] default scheme that will be used to challenge the client when no valid auth is provided
     * @param [additionalSchemes] additional schemes that will be accepted when validating the authentication
     */
    fun authSchemes(defaultScheme: String = "Bearer", vararg additionalSchemes: String) {
        schemes = JWTAuthSchemes(defaultScheme, *additionalSchemes)
    }

    /**
     * @param [verifier] verifies token format and signature
     */
    fun verifier(verifier: JWTVerifier) {
        this.verifier = { verifier }
    }

    /**
     * @param [verifier] verifies token format and signature
     */
    fun verifier(verifier: (HttpAuthHeader?) -> JWTVerifier?) {
        this.verifier = verifier
    }

    /**
     * @param [jwkProvider] provides the JSON Web Key
     * @param [issuer] the issuer of the JSON Web Token
     */
    fun verifier(jwkProvider: JwkProvider, issuer: String) {
        this.verifier = { token -> getVerifier(jwkProvider, issuer, token, schemes) }
    }

    fun validate(body: suspend ApplicationCall.(JWTCredential) -> Principal?) {
        authenticationFunction = body
    }
}

internal class JWTAuthSchemes(val defaultScheme: String, vararg val additionalSchemes: String) {
    val schemes = (arrayOf(defaultScheme) + additionalSchemes).toSet()
    val schemesLowerCase = schemes.map { it.toLowerCase() }.toSet()

    operator fun contains(scheme: String): Boolean = scheme.toLowerCase() in schemesLowerCase
}

/**
 * Installs JWT Authentication mechanism
 */
fun Authentication.Configuration.jwt(name: String? = null, configure: JWTAuthenticationProvider.() -> Unit) {
    val provider = JWTAuthenticationProvider(name).apply(configure)
    val realm = provider.realm
    val authenticate = provider.authenticationFunction
    val verifier = provider.verifier
    val schemes = provider.schemes
    provider.pipeline.intercept(AuthenticationPipeline.RequestAuthentication) { context ->
        val token = call.request.parseAuthorizationHeaderOrNull()
        val principal = verifyAndValidate(call, verifier(token), token, schemes, authenticate)
        evaluate(token, principal, realm, schemes, context)
    }
    register(provider)
}

private suspend fun evaluate(token: HttpAuthHeader?, principal: Principal?, realm: String, schemes: JWTAuthSchemes, context: AuthenticationContext) {
    val cause = when {
        token == null -> AuthenticationFailedCause.NoCredentials
        principal == null -> AuthenticationFailedCause.InvalidCredentials
        else -> null
    }
    if (cause != null) {
        context.challenge(JWTAuthKey, cause) {
            call.respond(UnauthorizedResponse(HttpAuthHeader.bearerAuthChallenge(realm, schemes)))
            it.complete()
        }
    }
    if (principal != null) {
        context.principal(principal)
    }
}

private fun getVerifier(jwkProvider: JwkProvider, issuer: String, token: HttpAuthHeader?, schemes: JWTAuthSchemes): JWTVerifier? {
    val jwk = token.getBlob(schemes)?.let { jwkProvider.get(JWT.decode(it).keyId) }

    val algorithm = try {
        jwk?.makeAlgorithm()
    } catch (ex: IllegalArgumentException) {
        null
    } ?: return null
    return JWT.require(algorithm).withIssuer(issuer).build()
}

private suspend fun verifyAndValidate(call: ApplicationCall, jwtVerifier: JWTVerifier?, token: HttpAuthHeader?, schemes: JWTAuthSchemes, validate: suspend ApplicationCall.(JWTCredential) -> Principal?): Principal? {
    val jwt = try {
        token.getBlob(schemes)?.let { jwtVerifier?.verify(it) }
    } catch (ex: JWTVerificationException) {
        null
    } ?: return null

    val payload = jwt.parsePayload()
    val credentials = JWTCredential(payload)
    return validate(call, credentials)
}

private fun HttpAuthHeader?.getBlob(schemes: JWTAuthSchemes) = when {
    this is HttpAuthHeader.Single && authScheme.toLowerCase() in schemes -> blob
    else -> null
}

private fun ApplicationRequest.parseAuthorizationHeaderOrNull() = try {
    parseAuthorizationHeader()
} catch (ex: IllegalArgumentException) {
    null
}

private fun HttpAuthHeader.Companion.bearerAuthChallenge(realm: String, schemes: JWTAuthSchemes): HttpAuthHeader =
        HttpAuthHeader.Parameterized(schemes.defaultScheme, mapOf(HttpAuthHeader.Parameters.Realm to realm))

private fun Jwk.makeAlgorithm(): Algorithm = when (algorithm) {
    "RS256" -> Algorithm.RSA256(publicKey as RSAPublicKey, null)
    "RS384" -> Algorithm.RSA384(publicKey as RSAPublicKey, null)
    "RS512" -> Algorithm.RSA512(publicKey as RSAPublicKey, null)
    "ES256" -> Algorithm.ECDSA256(publicKey as ECPublicKey, null)
    "ES384" -> Algorithm.ECDSA384(publicKey as ECPublicKey, null)
    "ES512" -> Algorithm.ECDSA512(publicKey as ECPublicKey, null)
    else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
}

private fun DecodedJWT.parsePayload(): Payload {
    val payloadString = String(Base64.getUrlDecoder().decode(payload))
    return JWTParser().parsePayload(payloadString)
}

@Deprecated("Use DSL builder form", replaceWith = ReplaceWith("jwt {\n" +
        "        this.realm = realm\n" +
        "        this.verifier(jwtVerifier)\n" +
        "        this.validate(validate)\n" +
        "    }\n"))
fun Authentication.Configuration.jwtAuthentication(jwtVerifier: JWTVerifier, realm: String, validate: suspend (JWTCredential) -> Principal?) {
    jwt {
        this.realm = realm
        this.verifier(jwtVerifier)
        this.validate { validate(it) }
    }
}

@Deprecated("Use DSL builder form", replaceWith = ReplaceWith("jwt {\n" +
        "        this.realm = realm\n" +
        "        this.verifier(jwkProvider, issuer)\n" +
        "        this.validate(validate)\n" +
        "    }\n"))
fun Authentication.Configuration.jwtAuthentication(jwkProvider: JwkProvider, issuer: String, realm: String, validate: suspend (JWTCredential) -> Principal?) {
    jwt {
        this.realm = realm
        this.verifier(jwkProvider, issuer)
        this.validate { validate(it) }
    }
}
