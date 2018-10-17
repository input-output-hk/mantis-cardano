package io.iohk.ethereum.jsonrpc.server

import java.io.{File, FileInputStream}
import java.security.{KeyStore, SecureRandom}

import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import io.iohk.ethereum.jsonrpc.server.http.JsonRpcHttpsServer.HttpsSetupResult
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import scala.io.Source
import scala.util.{Failure, Success, Try}

object SslSetup {
  case class CertificateConfig(
      keyStorePath: String,
      keyStoreType: String,
      passwordFile: String)
}

trait SslSetup {

  import SslSetup._

  def secureRandom: SecureRandom

  def certificateConfig: CertificateConfig

  lazy val maybeSslContext: Either[String, SSLContext] =
    validateCertificateFiles(certificateConfig).flatMap {
      case (keystorePath, keystoreType, passwordFile) =>
        val passwordReader = Source.fromFile(passwordFile)
        try {
          val password = passwordReader.getLines().mkString
          obtainSSLContext(keystorePath, keystoreType, password)
        } finally {
          passwordReader.close()
        }
    }

  lazy val maybeHttpsContext: Either[String, HttpsConnectionContext] = maybeSslContext.map(sslContext => ConnectionContext.https(sslContext))

  /**
    * Constructs the SSL context given a certificate
    *
    * @param certificateKeyStorePath, path to the keystore where the certificate is stored
    * @param password for accessing the keystore with the certificate
    * @return the SSL context with the obtained certificate or an error if any happened
    */
  protected def obtainSSLContext(certificateKeyStorePath: String, certificateKeyStoreType: String, password: String): HttpsSetupResult[SSLContext] = {
    val passwordCharArray: Array[Char] = password.toCharArray

    val maybeKeyStore: HttpsSetupResult[KeyStore] = Try(KeyStore.getInstance(certificateKeyStoreType))
      .toOption.toRight(s"Certificate keystore invalid type set: $certificateKeyStoreType")
    val keyStoreInitResult: HttpsSetupResult[KeyStore] = maybeKeyStore.flatMap{ keyStore =>
      val keyStoreFileCreationResult = Option(new FileInputStream(certificateKeyStorePath))
        .toRight("Certificate keystore file creation failed")
      keyStoreFileCreationResult.flatMap { keyStoreFile =>
        Try(keyStore.load(keyStoreFile, passwordCharArray)) match {
          case Success(_) => Right(keyStore)
          case Failure(err) => Left(err.getMessage)
        }
      }
    }

    keyStoreInitResult.map { ks =>
      val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(ks, passwordCharArray)

      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      tmf.init(ks)

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, secureRandom)
      sslContext
    }

  }

  /**
    * Validates that the keystore certificate file and password file were configured and that the files exists
    *
    * @return the certificate path and password file or the error detected
    */
  protected def validateCertificateFiles(certificateConfig: CertificateConfig): HttpsSetupResult[(String, String, String)] = {
    import certificateConfig._

    val keystoreDirMissing = !new File(keyStorePath).isFile
    val passwordFileMissing = !new File(passwordFile).isFile
    if(keystoreDirMissing && passwordFileMissing)
      Left("Certificate keystore path and password file configured but files are missing")
    else if(keystoreDirMissing)
      Left("Certificate keystore path configured but file is missing")
    else if(passwordFileMissing)
      Left("Certificate password file configured but file is missing")
    else
      Right((keyStorePath, keyStoreType, passwordFile))
  }

}
