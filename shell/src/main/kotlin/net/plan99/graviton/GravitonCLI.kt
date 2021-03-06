package net.plan99.graviton

import javafx.application.Application
import kotlinx.coroutines.experimental.runBlocking
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarStyle
import org.eclipse.aether.transfer.MetadataNotFoundException
import picocli.CommandLine
import java.lang.invoke.MethodHandles
import java.net.URI
import kotlin.coroutines.experimental.coroutineContext
import kotlin.math.max
import kotlin.system.exitProcess

val gravitonShellVersionNum: String get() = MethodHandles.lookup().lookupClass().`package`.implementationVersion.let { if (it.isNullOrBlank()) "DEV" else it }

@CommandLine.Command(
        name = "graviton",
        description = [
            "Graviton is an application browser and shell for the JVM. It will run and keep up to date programs from Maven repositories.",
            "If no arguments are specified, the GUI is invoked."
        ],
        mixinStandardHelpOptions = true,
        versionProvider = GravitonCLI.VersionProvider::class
)
class GravitonCLI : Runnable {
    companion object : Logging() {
        fun parse(text: String): GravitonCLI {
            val options = GravitonCLI()
            val cli = CommandLine(options)
            cli.isStopAtPositional = true
            cli.parse(*text.split(' ').toTypedArray())
            return options
        }
    }

    @CommandLine.Parameters(
            arity = "0..1",
            description = [
                "Maven coordinates of the package to run in the form of groupId:artifactId[:version]",
                "You can omit the version number to fetch the latest version."
            ]
    )
    var packageName: Array<String>? = null

    @CommandLine.Parameters(arity = "0..1", description = ["Arguments to pass to the invoked program"])
    var args: Array<String> = emptyArray()

    @CommandLine.Option(names = ["--clear-cache"], description = ["Deletes the contents of the app cache directory before starting."])
    var clearCache: Boolean = false

    @CommandLine.Option(names = ["--offline"], description = ["Skip checks against remote repositories for snapshot or LATEST versions."])
    var offline: Boolean = false

    // Invoked by the cron job we install, so don't show it in the help.
    @CommandLine.Option(names = ["--background-update"], hidden = true)
    var backgroundUpdate: Boolean = false

    @CommandLine.Option(names = ["--update-url"], hidden = true)
    var updateURL: String = "https://update.graviton.app/"

    // Just for development.
    @CommandLine.Option(names = ["--profile-downloads"], description = ["If larger than one downloads the coordinates the given number of times and prints statistics"], hidden = true)
    var profileDownloads: Int = -1

    @CommandLine.Option(names = ["--no-ssl"], description = ["If set, SSL encryption to the Maven repositories will be disabled. This can make downloads much faster, but also less safe."])
    var noSSL: Boolean = false

    @CommandLine.Option(names = ["--verbose"], description = ["Enable logging"])
    var verboseLogging: Boolean = false

    @CommandLine.Option(names = ["--default-coordinate"], description = ["The default launch coordinate put in the address bar of the browser shell, may contain command line arguments"])
    var defaultCoordinate: String = "com.github.ricksbrown:cowsay -f tux \"Hello world!\""

    @CommandLine.Option(names = ["--refresh", "-r"], description = ["Re-check with the servers to see if a newer version is available. A new version check occurs every 24 hours by default."])
    var refresh: Boolean = false

    @CommandLine.Option(names = ["--cache-path"], description = ["If specified, overrides the default cache directory."])
    var cachePath: String = currentOperatingSystem.appCacheDirectory.toString()

    override fun run() {
        // This is where Graviton startup really begins.
        val packageName = packageName
        setupLogging(verboseLogging)
        // TODO: Enable coloured output on Windows 10+, so client apps can use ANSI escapes without fear.
        if (GRAVITON_PATH != null && GRAVITON_VERSION != null) {
            // This will execute asynchronously.
            startupChecks(GRAVITON_PATH, GRAVITON_VERSION)
            val ls = System.lineSeparator()
            mainLog.info("$ls${ls}Starting Graviton $GRAVITON_VERSION$ls$ls")
            mainLog.info("Path is $GRAVITON_PATH")
        }
        if (backgroundUpdate) {
            mainLog.info("BACKGROUND UPDATE")
            BackgroundUpdates.doBackgroundUpdate(cachePath.toPath(), GRAVITON_VERSION?.toInt(), GRAVITON_PATH?.toPath(), URI.create(updateURL))
        } else {
            if (clearCache) {
                runBlocking {
                    HistoryManager.clearCache()
                }
            }
            if (packageName != null) {
                handleCommandLineInvocation(packageName[0])
            } else {
                Application.launch(GravitonBrowser::class.java, *args)
            }
        }
    }

    private fun handleCommandLineInvocation(coordinates: String) {
        runBlocking {
            if (profileDownloads > 1) downloadWithProfiling(coordinates)
            try {
                val manager = HistoryManager.create()
                val launcher = AppLauncher(this@GravitonCLI, manager, null, this.coroutineContext, createProgressBar())
                launcher.start()
            } catch (original: Throwable) {
                val e = original.rootCause
                if (e is MetadataNotFoundException) {
                    println("Sorry, that package is unknown. Check for typos? (${e.metadata})")
                } else if (e is IndexOutOfBoundsException) {
                    println("Sorry, could not understand that coordinate. Use groupId:artifactId syntax.")
                } else {
                    val msg = e.message
                    if (msg != null)
                        println(msg)
                    else
                        e.printStackTrace()
                }
            }
        }
    }

    private fun createProgressBar(): CodeFetcher.Events {
        val pb = ProgressBar("Update", 1, 100, System.out, ProgressBarStyle.ASCII)
        return object : CodeFetcher.Events {
            val stopwatch = Stopwatch()

            override suspend fun onStartedDownloading(name: String) {
                pb.start()
                pb.extraMessage = name
            }

            override suspend fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {
                pb.extraMessage = name
                // The ProgressBar library gets unhappy if we use ranges like 0/0 - it works but doesn't expand
                // to fill the terminal so we get visual artifacts.
                pb.maxHint(max(1, totalBytesToDownload / 1024))
                pb.stepTo(totalDownloadedSoFar / 1024)
            }

            override suspend fun onStoppedDownloading() {
                pb.stop()
                println("Downloaded successfully in ${stopwatch.elapsedInSec} seconds")
            }
        }
    }

    private suspend fun downloadWithProfiling(coordinates: String) {
        val codeFetcher = CodeFetcher(coroutineContext, cachePath.toPath())
        codeFetcher.offline = offline
        codeFetcher.useSSL = !noSSL
        val stopwatch = Stopwatch()
        repeat(profileDownloads) {
            HistoryManager.clearCache()
            codeFetcher.events = createProgressBar()
            codeFetcher.downloadAndBuildClasspath(coordinates)
        }
        val totalSec = stopwatch.elapsedInSec
        println("Total runtime was $totalSec, for an average of ${totalSec / profileDownloads} seconds per run.")
        exitProcess(0)
    }

    class VersionProvider : CommandLine.IVersionProvider {
        override fun getVersion(): Array<String> {
            return arrayOf(gravitonShellVersionNum)
        }
    }
}
