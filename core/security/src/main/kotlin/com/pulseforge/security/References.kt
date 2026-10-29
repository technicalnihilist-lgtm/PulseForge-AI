package com.pulseforge.security

import com.pulseforge.model.Reference

/**
 * Central place for the external frameworks findings map to, so every rule cites the same
 * identifiers and the report can be audited.
 */
object References {

    private const val CWE_URL = "https://cwe.mitre.org/data/definitions/%s.html"
    private const val MASVS_URL = "https://mas.owasp.org/MASVS/"

    fun cwe(id: String, title: String): Reference = Reference("CWE", "CWE-$id", title, CWE_URL.format(id))

    fun masvs(id: String, title: String): Reference = Reference("OWASP MASVS", id, title, MASVS_URL)

    val cweExport = cwe("926", "Improper Export of Android Application Components")
    val cweIntentFilter = cwe("925", "Improper Restriction of Communication Channel to Intended Endpoints")
    val cweDebugCode = cwe("489", "Active Debug Code")
    val cweCleartext = cwe("319", "Cleartext Transmission of Sensitive Information")
    val cweCleartextStorage = cwe("312", "Cleartext Storage of Sensitive Information")
    val cweHardcoded = cwe("798", "Use of Hard-coded Credentials")
    val cweWeakCrypto = cwe("327", "Use of a Broken or Risky Cryptographic Algorithm")
    val cweWeakHash = cwe("328", "Use of Weak Hash")
    val cweWeakRandom = cwe("330", "Use of Insufficiently Random Values")
    val cweLogLeak = cwe("532", "Insertion of Sensitive Information into Log File")
    val cweCertValidation = cwe("295", "Improper Certificate Validation")
    val cweExposedMethod = cwe("749", "Exposed Dangerous Method or Function")
    val cweFileAccess = cwe("552", "Files or Directories Accessible to External Parties")
    val cweCommandInjection = cwe("78", "Improper Neutralization of Special Elements used in an OS Command")
    val cweDynamicCode = cwe("494", "Download of Code Without Integrity Check")
    val cweUnnecessaryPrivileges = cwe("250", "Execution with Unnecessary Privileges")
    val cweUnmaintained = cwe("1104", "Use of Unmaintained Third Party Components")
    val cweKeyLength = cwe("326", "Inadequate Encryption Strength")
    val cwePathTraversal = cwe("22", "Improper Limitation of a Pathname to a Restricted Directory")

    val masvsStorage1 = masvs("MASVS-STORAGE-1", "The app securely stores sensitive data")
    val masvsStorage2 = masvs("MASVS-STORAGE-2", "The app prevents leakage of sensitive data")
    val masvsCrypto1 = masvs("MASVS-CRYPTO-1", "The app uses strong, well-configured cryptography")
    val masvsNetwork1 = masvs("MASVS-NETWORK-1", "The app secures sensitive data in transit")
    val masvsNetwork2 = masvs("MASVS-NETWORK-2", "The app performs identity pinning")
    val masvsPlatform1 = masvs("MASVS-PLATFORM-1", "The app uses IPC mechanisms securely")
    val masvsPlatform2 = masvs("MASVS-PLATFORM-2", "The app uses WebViews securely")
    val masvsPlatform3 = masvs("MASVS-PLATFORM-3", "The app uses the UI securely")
    val masvsCode1 = masvs("MASVS-CODE-1", "The app is built with a current, hardened toolchain")
    val masvsCode4 = masvs("MASVS-CODE-4", "The app does not contain debug code or test artefacts")
    val masvsPrivacy1 = masvs("MASVS-PRIVACY-1", "The app minimises its use of permissions")
    val masvsPrivacy2 = masvs("MASVS-PRIVACY-2", "The app handles user data transparently")
    val masvsResilience1 = masvs("MASVS-RESILIENCE-1", "The app validates platform integrity")
}
