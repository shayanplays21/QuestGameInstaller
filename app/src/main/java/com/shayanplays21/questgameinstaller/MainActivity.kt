package com.shayanplays21.questgameinstaller

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {
    private var apkUri: Uri? = null
    private var sourceTreeUri: Uri? = null
    private var destinationTreeUri: Uri? = null
    private var detectedPackage: String? = null

    private lateinit var status: TextView
    private lateinit var apkInfo: TextView
    private lateinit var sourceInfo: TextView
    private lateinit var destinationInfo: TextView

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
                    "Selected APK\nPackage: $detectedPackage"
                } else {
                    "Selected APK\nCould not read package information."
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
            sourceInfo.text = "Source: ${folder?.name ?: "selected folder"}"
            status.text = "Data folder selected. Choose a writable destination."
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
            destinationInfo.text = "Destination: ${folder?.name ?: "selected folder"}"
            status.text = "Destination selected. You can copy the data folder."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        apkInfo = findViewById(R.id.apkInfoText)
        sourceInfo = findViewById(R.id.sourceInfoText)
        destinationInfo = findViewById(R.id.destinationInfoText)

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
    }

    private fun installSelectedApk() {
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
            status.text = "Android package installer opened. Confirm the installation there."
        } catch (e: Exception) {
            status.text = "Could not open package installer: ${e.message}"
        }
    }

    private fun copySelectedFolder() {
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
                val targetDir = destination.findFile(child.name ?: "Folder")
                    ?: destination.createDirectory(child.name ?: "Folder")
                    ?: throw IllegalStateException("Cannot create folder: ${child.name}")
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
