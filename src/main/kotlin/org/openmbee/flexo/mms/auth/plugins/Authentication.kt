package org.openmbee.flexo.mms.auth.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.config.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.openmbee.flexo.mms.auth.UserDetailsPrincipal
import org.openmbee.flexo.mms.auth.ldapAuthenticate
import org.openmbee.flexo.mms.auth.ldapEscape
import java.util.*

@OptIn(InternalAPI::class)
fun Application.configureAuthentication() {
    val ldapConfigValues = getLdapConfValues(environment.config)
    val environment = environment
    authentication {
        basic(name = "localAuth") {
            realm = "Flexo MMS Basic"
            validate { credentials ->
                if (credentials.name == credentials.password) UserIdPrincipal(credentials.name) else null
            }
        }

        basic("ldapAuth") {
            realm = "Flexo MMS LDAP"
            validate { credential ->
                val client = HttpClient(CIO)

                val rootContext = environment.config.property("ldap.groupStore.context").getString()
                val storeUri = environment.config.property("ldap.groupStore.uri").getString()
                val sparql =
                    """
                        prefix mms: <https://mms.openmbee.org/rdf/ontology/>
                        base <$rootContext>
                        prefix m: <>
                        prefix m-graph: <graphs/>
                        select ?groupId from m-graph:AccessControl.Agents {
                            ?group a mms:Group ;
                            mms:id ?groupId ;
                            .
                            filter strStarts(?groupId, "${ldapConfigValues.groupNamespace}")
                        }
                    """.trimIndent()

                application.log.debug(sparql)

                val response = client.post(storeUri) {
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                    }
                    contentType(ContentType("application", "sparql-query"))
                    body = sparql
                }

                val responseText = response.bodyAsText()

                application.log.debug(responseText)

                val responseJson = Json.parseToJsonElement(responseText).jsonObject
                val bindings: MutableList<String> =
                    responseJson["results"]!!.jsonObject["bindings"]!!.jsonArray.map { jsonElement ->
                        val jsonObject = jsonElement.jsonObject
                        val groupId = jsonObject["groupId"]!!.jsonObject["value"]
                            .toString()
                            .removeSurrounding("\"")
                            .replace(ldapConfigValues.groupNamespace, ldapConfigValues.groupAttribute + "=")
                            .split(",")
                        return@map groupId[0]
                    } as MutableList<String>
                if (bindings.isEmpty()) {
                    bindings.add(ldapConfigValues.groupAttribute + "=all.personnel")
                }

                application.log.info(
                    ldapConfigValues.groupSearch.format(
                        ldapConfigValues.userDnPattern.format(ldapEscape(credential.name)),
                        bindings.joinToString(")(")
                    )
                )

                ldapAuthenticate(
                    credential,
                    ldapConfigValues.serverLocation,
                    ldapConfigValues.userDnPattern,
                    ldapConfigValues.userNamespace,
                    ldapConfigValues.base,
                    ldapConfigValues.groupAttribute,
                    ldapConfigValues.groupSearch.format(
                        ldapConfigValues.userDnPattern.format(ldapEscape(credential.name)),
                        bindings.joinToString(")(")
                    ),
                    ldapConfigValues.groupNamespace
                )
            }
        }

        jwt("jwtAuth") {
            val jwtAudience = environment.config.property("jwt.audience").getString()
            val issuer = environment.config.property("jwt.domain").getString()
            val secret = environment.config.property("jwt.secret").getString()
            realm = environment.config.property("jwt.realm").getString()
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(jwtAudience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) {
                    UserDetailsPrincipal(
                        credential.payload.claims["username"]?.asString() ?: "",
                        credential.payload.claims["groups"]?.asList("".javaClass) ?: emptyList()
                    )
                } else null
            }
        }
    }
}

data class LdapConfig(
    val serverLocation: String,
    val base: String,
    val userDnPattern: String,
    val userNamespace: String,
    val groupAttribute: String,
    val groupSearch: String,
    val groupNamespace: String
)

fun getLdapConfValues(config: ApplicationConfig): LdapConfig {
    val ldapServerLocation = config.propertyOrNull("ldap.location")?.getString() ?: ""
    val ldapBase = config.propertyOrNull("ldap.base")?.getString() ?: ""
    val ldapUserDnPattern = (config.propertyOrNull("ldap.userPattern")?.getString() + "," + ldapBase)
    val ldapUserNamespace = config.propertyOrNull("ldap.userNamespace")?.getString() ?: ""
    val ldapGroupAttribute = config.propertyOrNull("ldap.groupAttribute")?.getString() ?: ""
    val ldapGroupSearch = config.propertyOrNull("ldap.groupSearchFilter")?.getString() ?: ""
    val ldapGroupNamespace = config.propertyOrNull("ldap.groupNamespace")?.getString() ?: ""
    return LdapConfig(
        ldapServerLocation,
        ldapBase,
        ldapUserDnPattern,
        ldapUserNamespace,
        ldapGroupAttribute,
        ldapGroupSearch,
        ldapGroupNamespace
    )
}

fun generateJWT(audience: String, issuer: String, secret: String, principal: UserDetailsPrincipal): String {
    val expires = Date(System.currentTimeMillis() + (1 * 24 * 60 * 60 * 1000))
    return JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("username", principal.name)
        .withClaim("groups", principal.groups)
        .withExpiresAt(expires)
        .sign(Algorithm.HMAC256(secret))
}
