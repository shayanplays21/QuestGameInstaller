package com.shayanplays21.questgameinstaller

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var apkUri: Uri? = null
    private var sourceTreeUri: Uri? = null
    private var destinationTreeUri: Uri? = null
    private var detectedPackage: String? = null

    private lateinit var status: TextView
    private lateinit var apkInfo: TextView
    private lateinit var sourceInfo: TextView
    private lateinit var destinationInfo: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuDetail: TextView

    private val permissionRequestCode = 7301

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshShizukuStatus() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshShizukuStatus() }
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == permissionRequestCode) {
                runOnUiThread {
                    refreshShizukuStatus()
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        status.text = "Shizuku connected and permission granted."
                        toast("Shizuku connected.")
                    } else {
                        status.text = "Shizuku permission was denied. Approve it in Shizuku to continue."
                    }
                }
            }
        }

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            apkUri = uri
            val temp = cacheDir.resolve("selected-game.apk")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                val info = packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                detectedPackage = info?.packageName
                apkInfo.text = if (detectedPackage != null) {
                    "Package ID detected\n$detectedPackage"
                } else {
                    "Could not read package information from this APK."
                }
                status.text = "APK selected. Choose the game's data folder."
            } catch (e: Exception) {
                apkInfo.text = "Could not inspect APK: ${e.message}"
            }
        }
    }

    private val sourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            sourceTreeUri = uri
            val folder = DocumentFile.fromTreeUri(this, uri)
            sourceInfo.text = "Source folder\n${folder?.name ?: "Selected folder"}"
            status.text = "Source data folder selected."
        }
    }

    private val destinationPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destinationTreeUri = uri
            val folder = DocumentFile.fromTreeUri(this, uri)
            destinationInfo.text = "Destination folder\n${folder?.name ?: "Selected folder"}"
            status.text = "Destination selected."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        apkInfo = findViewById(R.id.apkInfoText)
        sourceInfo = findViewById(R.id.sourceInfoText)
        destinationInfo = findViewById(R.id.destinationInfoText)
        shizukuStatus = findViewById(R.id.shizukuStatusText)
        shizukuDetail = findViewById(R.id.shizukuDetailText)

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        findViewById<Button>(R.id.connectShizukuButton).setOnClickListener {
            connectToShizuku()
        }
        findViewById<Button>(R.id.chooseApkButton).setOnClickListener {
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        findViewById<Button>(R.id.chooseSourceButton).setOnClickListener {
            sourcePicker.launch(null)
        }
        findViewById<Button>(R.id.chooseDestinationButton).setOnClickListener {
            destinationPicker.launch(null)
        }
        findViewById<Button>(R.id.installButton).setOnClickListener { installSelectedApk() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copySelectedFolder() }

        refreshShizukuStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun connectToShizuku() {
        if (!isShizukuInstalled()) {
            shizukuStatus.text = "Shizuku is not installed"
            shizukuDetail.text = "Install the Shizuku app, start its service, then return here."
            status.text = "Shizuku is required."
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            } catch (_: Exception) {
                toast("Visit shizuku.rikka.app/download/")
            }
            return
        }

        if (!Shizuku.pingBinder()) {
            shizukuStatus.text = "Shizuku service is offline"
            shizukuDetail.text = "Open Shizuku and start the service using Wireless debugging or root. Then return here."
            status.text = "Start the Shizuku service, then tap Connect again."
            launchShizuku()
            return
        }

        try {
            if (Shizuku.isPreV11()) {
                shizukuStatus.text = "Unsupported Shizuku version"
                shizukuDetail.text = "Update the Shizuku app to a version compatible with the current API."
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshShizukuStatus()
                status.text = "Shizuku is connected and authorized."
            } else {
                Shizuku.requestPermission(permissionRequestCode)
                shizukuDetail.text = "Approve the permission prompt from Shizuku."
            }
        } catch (e: Exception) {
            shizukuStatus.text = "Connection error"
            shizukuDetail.text = e.message ?: "Could not communicate with Shizuku."
        }
    }

    private fun refreshShizukuStatus() {
        if (!::shizukuStatus.isInitialized) return
        if (!isShizukuInstalled()) {
            shizukuStatus.text = "Shizuku not installed"
            shizukuDetail.text = "Install Shizuku to use this installer."
            return
        }
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatus.text = "Service not running"
                shizukuDetail.text = "Start Shizuku on the headset, then connect here."
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuStatus.text = "Connected • Authorized"
                shizukuDetail.text = "Shizuku is running and this app has permission."
            } else {
                shizukuStatus.text = "Connected • Permission needed"
                shizukuDetail.text = "Tap Connect to Shizuku and approve the request."
            }
        } catch (_: Exception) {
            shizukuStatus.text = "Waiting for Shizuku"
            shizukuDetail.text = "Start the Shizuku service and try again."
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun launchShizuku() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) startActivity(intent)
        else toast("Open Shizuku manually and start its service.")
    }

    private fun requireShizukuPermission(): Boolean {
        if (!isShizukuInstalled() || !Shizuku.pingBinder()) {
            toast("Connect to Shizuku first.")
            refreshShizukuStatus()
            return false
        }
        return try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) true
            else {
                toast("Grant Shizuku permission first.")
                connectToShizuku()
                false
            }
        } catch (_: Exception) {
            toast("Shizuku is not ready.")
            false
        }
    }

    private fun installSelectedApk() {
        if (!requireShizukuPermission()) return
        val uri = apkUri
        if (uri == null) {
            toast("Choose an APK first.")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
            status.text = "Android package installer opened. Confirm installation there."
        } catch (e: Exception) {
            status.text = "Could not open package installer: ${e.message}"
        }
    }

    private fun copySelectedFolder() {
        if (!requireShizukuPermission()) return
        val sourceUri = sourceTreeUri
        val destinationUri = destinationTreeUri
        if (sourceUri == null || destinationUri == null) {
            toast("Choose both a source folder and a destination.")
            return
        }
        try {
            val source = DocumentFile.fromTreeUri(this, sourceUri)
                ?: throw IllegalStateException("Cannot open source folder")
            val destination = DocumentFile.fromTreeUri(this, destinationUri)
                ?: throw IllegalStateException("Cannot open destination folder")
            val folderName = source.name ?: "GameData"
            val target = destination.findFile(folderName)
                ?: destination.createDirectory(folderName)
                ?: throw IllegalStateException("Cannot create destination folder")
            copyChildren(source, target)
            status.text = "Copy complete: $folderName"
            toast("Game data copied.")
        } catch (e: Exception) {
            status.text = "Copy failed: ${e.message}"
            toast("Copy failed: ${e.message}")
        }
    }

    private fun copyChildren(source: DocumentFile, destination: DocumentFile) {
        for (child in source.listFiles()) {
            if (child.isDirectory) {
                val name = child.name ?: "Folder"
                val targetDir = destination.findFile(name)
                    ?: destination.createDirectory(name)
                    ?: throw IllegalStateException("Cannot create folder: $name")
                copyChildren(child, targetDir)
            } else if (child.isFile) {
                val name = child.name ?: "file"
                val mime = child.type ?: "application/octet-stream"
                val targetFile = destination.findFile(name)?.also { it.delete() }
                    ?: destination.createFile(mime, name)
                    ?: throw IllegalStateException("Cannot create file: $name")
                contentResolver.openInputStream(child.uri).use { input ->
                    if (input == null) throw IllegalStateException("Cannot read: $name")
                    contentResolver.openOutputStream(targetFile.uri, "w").use { output ->
                        if (output == null) throw IllegalStateException("Cannot write: $name")
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}