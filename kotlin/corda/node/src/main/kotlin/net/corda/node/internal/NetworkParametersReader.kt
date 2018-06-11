package net.corda.node.internal

import net.corda.core.crypto.SecureHash
import net.corda.core.internal.*
import net.corda.core.node.NetworkParameters
import net.corda.core.serialization.serialize
import net.corda.core.utilities.contextLogger
import net.corda.node.services.network.NetworkMapClient
import net.corda.nodeapi.internal.network.NETWORK_PARAMS_FILE_NAME
import net.corda.nodeapi.internal.network.NETWORK_PARAMS_UPDATE_FILE_NAME
import net.corda.nodeapi.internal.network.SignedNetworkParameters
import net.corda.nodeapi.internal.network.verifiedNetworkMapCert
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate

class NetworkParametersReader(private val trustRoot: X509Certificate,
                              private val networkMapClient: NetworkMapClient?,
                              private val baseDirectory: Path) {
    companion object {
        private val logger = contextLogger()
    }

    private data class NetworkParamsAndHash(val networkParameters: NetworkParameters, val hash: SecureHash)
    private val networkParamsFile = baseDirectory / NETWORK_PARAMS_FILE_NAME
    private val parametersUpdateFile = baseDirectory / NETWORK_PARAMS_UPDATE_FILE_NAME
    private val netParamsAndHash by lazy { retrieveNetworkParameters() }
    val networkParameters get() = netParamsAndHash.networkParameters
    val hash get() = netParamsAndHash.hash

    private fun retrieveNetworkParameters(): NetworkParamsAndHash {
        val advertisedParametersHash = try {
            networkMapClient?.getNetworkMap()?.payload?.networkParameterHash
        } catch (e: Exception) {
            logger.info("Unable to download network map", e)
            // If NetworkMap is down while restarting the node, we should be still able to continue with parameters from file
            null
        }
        val signedParametersFromFile = if (networkParamsFile.exists()) {
            networkParamsFile.readObject<SignedNetworkParameters>()
        } else {
            null
        }
        val parameters = if (advertisedParametersHash != null) {
            // TODO On one hand we have node starting without parameters and just accepting them by default,
            //  on the other we have parameters update process - it needs to be unified. Say you start the node, you don't have matching parameters,
            //  you get them from network map, but you have to run the approval step.
            if (signedParametersFromFile == null) { // Node joins for the first time.
                downloadParameters(advertisedParametersHash)
            } else if (signedParametersFromFile.raw.hash == advertisedParametersHash) { // Restarted with the same parameters.
                signedParametersFromFile
            } else { // Update case.
                readParametersUpdate(advertisedParametersHash, signedParametersFromFile.raw.hash)
            }
        } else { // No compatibility zone configured. Node should proceed with parameters from file.
            signedParametersFromFile ?: throw IllegalArgumentException("Couldn't find network parameters file and compatibility zone wasn't configured/isn't reachable")
        }
        logger.info("Loaded network parameters: $parameters")
        return NetworkParamsAndHash(parameters.verifiedNetworkMapCert(trustRoot), parameters.raw.hash)
    }

    private fun readParametersUpdate(advertisedParametersHash: SecureHash, previousParametersHash: SecureHash): SignedNetworkParameters {
        if (!parametersUpdateFile.exists()) {
            throw IllegalArgumentException("Node uses parameters with hash: $previousParametersHash " +
                    "but network map is advertising: $advertisedParametersHash.\n" +
                    "Please update node to use correct network parameters file.")
        }
        val signedUpdatedParameters = parametersUpdateFile.readObject<SignedNetworkParameters>()
        if (signedUpdatedParameters.raw.hash != advertisedParametersHash) {
            throw IllegalArgumentException("Both network parameters and network parameters update files don't match" +
                    "parameters advertised by network map.\n" +
                    "Please update node to use correct network parameters file.")
        }
        parametersUpdateFile.moveTo(networkParamsFile, StandardCopyOption.REPLACE_EXISTING)
        logger.info("Scheduled update to network parameters has occurred - node now updated to these new parameters.")
        return signedUpdatedParameters
    }

    // Used only when node joins for the first time.
    private fun downloadParameters(parametersHash: SecureHash): SignedNetworkParameters {
        logger.info("No network-parameters file found. Expecting network parameters to be available from the network map.")
        val networkMapClient = checkNotNull(networkMapClient) {
            "Node hasn't been configured to connect to a network map from which to get the network parameters"
        }
        val signedParams = networkMapClient.getNetworkParameters(parametersHash)
        signedParams.serialize().open().copyTo(baseDirectory / NETWORK_PARAMS_FILE_NAME)
        return signedParams
    }
}
