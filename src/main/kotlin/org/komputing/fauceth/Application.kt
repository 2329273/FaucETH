package org.komputing.fauceth

import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import io.ktor.application.*
import io.ktor.html.respondHtml
import io.ktor.http.content.*
import io.ktor.request.receiveParameters
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import kotlinx.html.*
import org.kethereum.DEFAULT_GAS_LIMIT
import org.kethereum.ETH_IN_WEI
import org.kethereum.crypto.createEthereumKeyPair
import org.kethereum.crypto.toAddress
import org.kethereum.crypto.toECKeyPair
import org.kethereum.eip155.signViaEIP155
import org.kethereum.extensions.transactions.encodeLegacyTxRLP
import org.kethereum.model.*
import org.kethereum.rpc.HttpEthereumRPC
import org.walleth.khex.toHexString
import java.io.File
import java.math.BigInteger

const val ADDRESS_KEY = "address"
val keystoreFile = File("fauceth_keystore.json")

val config = systemProperties() overriding
        EnvironmentVariables() overriding
        ConfigurationProperties.fromFile(File("fauceth.properties"))

lateinit var keyPair: ECKeyPair
lateinit var hcaptchaSiteKey: String
lateinit var hcaptchaSecret: String

data class ChainConfig(
    val name: String,
    val chainId: BigInteger,
    val explorer: String?
)

fun main(args: Array<String>) {
    hcaptchaSecret = config[Key("hcaptcha.secret", stringType)]
    hcaptchaSiteKey = config[Key("hcaptcha.sitekey", stringType)]

    if (!keystoreFile.exists()) {
        keyPair = createEthereumKeyPair()
        keystoreFile.createNewFile()
        keystoreFile.writeText(keyPair.privateKey.key.toString())
    } else {
        keyPair = PrivateKey(keystoreFile.readText().toBigInteger()).toECKeyPair()
    }

    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val chainConfig = ChainConfig("kintsugi", BigInteger.valueOf(1337702), "https://explorer.kintsugi.themerge.dev/")
    routing {
        static("/static") {
            resources("files")
        }
        get("/") {
            val address = call.request.queryParameters[ADDRESS_KEY]
            render(address, null)
        }
        post("/") {
            val receiveParameters = call.receiveParameters()

            val captchaResult: Boolean = verifyCaptcha(receiveParameters["h-captcha-response"] ?: "", hcaptchaSecret)
            val address = receiveParameters[ADDRESS_KEY]
            if (address?.length != 42) {
                render(address, DialogDefinition("Error", "Address invalid", "error"))
            } else if (!captchaResult) {
                render(address, DialogDefinition("Error", "Could not verify your humanity", "error"))
            } else {

                val rpc = HttpEthereumRPC("https://rpc.kintsugi.themerge.dev")
                val nonce = rpc.getTransactionCount(keyPair.toAddress())
                val tx = createEmptyTransaction().apply {
                    to = Address(address)
                    value = ETH_IN_WEI
                    this.nonce = nonce
                    gasLimit = DEFAULT_GAS_LIMIT
                    gasPrice = BigInteger.valueOf(1000000000)
                    chain = chainConfig.chainId
                }

                val signature = tx.signViaEIP155(keyPair, ChainId(tx.chain!!))
                val res = rpc.sendRawTransaction(tx.encodeLegacyTxRLP(signature).toHexString())
                val msg = if (chainConfig.explorer != null) {
                    "send 1 ETH (<a href='${chainConfig.explorer}/tx/$res'>view here</a>)"
                } else {
                    "send 1 ETH (transaction: $res)"
                }
                render(address,  DialogDefinition("Transaction send", msg, "success"))
            }
        }


    }

}

class DialogDefinition(val title: String, val msg: String, val type: String)

private suspend fun PipelineContext<Unit, ApplicationCall>.render(prefillAddress: String?, dlg: DialogDefinition?) {

    call.respondHtml {
        head {
            title { +"FaucETH" }

            script(src = "https://js.hcaptcha.com/1/api.js") {}

            styleLink("/static/css/sweetalert2.min.css")
            script(src = "/static/js/sweetalert2.all.min.js") {}
        }
        body {
            p {
                +"FaucETH"
            }
            form {
                input {
                    name = ADDRESS_KEY
                    value = prefillAddress ?: ""
                    placeholder = "Please enter an address"
                }
                br
                postButton {
                    +"Request funds"
                }
                div(classes = "h-captcha") {
                    attributes["data-sitekey"] = hcaptchaSiteKey
                }
            }
            dlg?.let { alert(it) }
        }
    }
}

private fun BODY.alert(dlg: DialogDefinition) {
    unsafe {
        +"""
         <script>
         Swal.fire("${dlg.title}","${dlg.msg}","${dlg.type}")
         </script
         """.trimIndent()
    }
}
