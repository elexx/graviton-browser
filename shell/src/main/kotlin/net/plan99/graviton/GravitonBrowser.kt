package net.plan99.graviton

import de.codecentric.centerdevice.MenuToolkit
import de.codecentric.centerdevice.dialogs.about.AboutStageBuilder
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.StringProperty
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.TextArea
import javafx.scene.effect.DropShadow
import javafx.scene.image.Image
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.text.Font
import javafx.scene.text.TextAlignment
import javafx.stage.Stage
import javafx.stage.StageStyle
import kotlinx.coroutines.experimental.javafx.JavaFx
import kotlinx.coroutines.experimental.launch
import org.eclipse.aether.transfer.ArtifactNotFoundException
import tornadofx.*
import java.io.OutputStream
import java.io.PrintStream
import java.util.*
import javax.imageio.ImageIO
import kotlin.concurrent.thread


// TODO: Organise all attribution links for artwork in the about box, once there is one.
// icons8.com
// OFL 1.1 license for the font
// vexels for the vector art

// Allow for minimal rebranding in future.
val APP_NAME = "Graviton"
val Component.APP_LOGO get() = Image(resources["art/icons8-rocket-take-off-128.png"])

class GravitonBrowser : App(ShellView::class, Styles::class) {
    init {
        importStylesheet("/net/plan99/graviton/graviton.css")
    }

    override fun start(stage: Stage) {
        stage.icons.addAll(
                Image(resources["art/icons8-rocket-take-off-128.png"]),
                Image(resources["/net/plan99/graviton/art/icons8-rocket-take-off-512.png"]),
                Image(resources["art/icons8-rocket-take-off-64.png"])
        )
        stage.isMaximized = true
        if (currentOperatingSystem == OperatingSystem.MAC) {
            // This looks nice on OS X but not so great on other platforms. Note that it doesn't work on Java 8, but looks right on
            // Java 10. Once we upgrade we'll get it back.
            stage.initStyle(StageStyle.UNIFIED)
            val dockImage = ImageIO.read(resources.stream("art/icons8-rocket-take-off-512.png"))
            // This is a PITA - stage.icons doesn't work on macOS, instead there's two other APIs, one for Java 8 and one for post-J8.
            // TODO: In Java 9+ there is a different API for this:
            // Taskbar.getTaskbar().setIconImage(icon);
            Class.forName("com.apple.eawt.Application")
                    ?.getMethod("getApplication")
                    ?.invoke(null)?.let { app ->
                        app.javaClass
                           .getMethod("setDockIconImage", java.awt.Image::class.java)
                           .invoke(app, dockImage)
                    }
            stage.title = "Graviton"
        }

        super.start(stage)
    }
}

class ShellView : View() {
    companion object : Logging()

    private val downloadProgress = SimpleDoubleProperty(0.0)
    private val isDownloading = SimpleBooleanProperty()
    private lateinit var progressAnimation: ThreeDSpinner
    private lateinit var messageText1: StringProperty
    private lateinit var messageText2: StringProperty
    private lateinit var outputArea: TextArea

    private val historyManager by lazy { HistoryManager.create() }

    // Build up the UI layouts and widgets using the TornadoFX DSL.
    override val root = stackpane {
        style { backgroundColor = multi(Color.WHITE) }

        if (currentOperatingSystem == OperatingSystem.MAC) {
            // On macOS we can't avoid having a menu bar, and anyway it's a good place to stash an about box
            // and other such things. TODO: Consider a menu strategy on other platforms.
            setupMacMenuBar()
        }

        data class Art(val fileName: String, val topPadding: Int, val animationColor: Color, val topGradient: Paint)

        val allArt = listOf(
                Art("paris.png", 200, Color.BLUE, Color.WHITE),
                Art("forest.jpg", 200,
                        Color.color(0.0, 0.5019608, 0.0, 0.5),
                        LinearGradient.valueOf("transparent,rgb(218,239,244)")
                )
        )
        val art = allArt[1]

        vbox {
            stackpane {
                style {
                    backgroundColor = multi(art.topGradient)
                }
                vbox {
                    minHeight = art.topPadding.toDouble()
                }
            }
            // Background image.
            imageview {
                image = Image(resources["art/${art.fileName}"])
                fitWidthProperty().bind(this@stackpane.widthProperty())
                isPreserveRatio = true

                 setOnMouseClicked { isDownloading.set(!isDownloading.value) }
            }.stackpaneConstraints {
                alignment = Pos.BOTTOM_CENTER
            }
        }.stackpaneConstraints { alignment = Pos.TOP_CENTER }

        progressAnimation = ThreeDSpinner(art.animationColor)
        progressAnimation.root.maxWidth = 600.0
        progressAnimation.root.maxHeight = 600.0
        progressAnimation.root.translateY = 0.0
        children += progressAnimation.root
        progressAnimation.visible.bind(isDownloading)

        vbox {
            pane { minHeight = 0.0 }

            hbox {
                alignment = Pos.CENTER
                imageview(APP_LOGO)
                label("graviton") {
                    addClass(Styles.logoText)
                }
            }

            pane { minHeight = 25.0 }

            textfield {
                style {
                    fontSize = 20.pt
                    alignment = Pos.CENTER
                }
                text = commandLineArguments.defaultCoordinate
                selectAll()
                disableProperty().bind(isDownloading)
                action { onNavigate(text) }
            }

            pane { minHeight = 25.0 }

            vbox {
                addClass(Styles.messageBox)
                padding = insets(15.0)
                alignment = Pos.CENTER
                label {
                    messageText1 = textProperty()
                    textAlignment = TextAlignment.CENTER
                }
                label {
                    messageText2 = textProperty()
                    textAlignment = TextAlignment.CENTER
                }
                visibleProperty().bind(messageText1.isNotEmpty.or(messageText2.isNotEmpty))
            }

            pane { minHeight = 25.0 }

            outputArea = textarea {
                addClass(Styles.shellArea)
                isWrapText = false
                opacity = 0.0
                textProperty().addListener { _, oldValue, newValue ->
                    if (oldValue.isBlank() && newValue.isNotBlank()) {
                        opacityProperty().animate(1.0, 0.3.seconds)
                    } else if (newValue.isBlank() && oldValue.isNotBlank()) {
                        opacityProperty().animate(0.0, 0.3.seconds)
                    }
                }
                prefRowCountProperty().bind(Bindings.`when`(textProperty().isNotEmpty).then(20).otherwise(0))
            }

            maxWidth = 800.0
            spacing = 5.0
            alignment = Pos.TOP_CENTER
        }

        label("Background art by Vexels") {
            style {
                padding = box(10.px)
            }
        }.stackpaneConstraints { alignment = Pos.BOTTOM_RIGHT }
    }

    private fun setupMacMenuBar() {
        val tk = MenuToolkit.toolkit()
        val aboutStage = AboutStageBuilder
                .start("About $APP_NAME")
                .withAppName(APP_NAME)
                .withCloseOnFocusLoss()
                .withVersionString("Version $gravitonShellVersionNum")
                .withCopyright("Copyright \u00A9 " + Calendar.getInstance().get(Calendar.YEAR))
                .withImage(APP_LOGO)
                .build()

        menubar {
            val appMenu = menu(APP_NAME) {
                // Note that the app menu name can't be changed at runtime and will be ignored; to make the menu bar say Graviton
                // requires bundling it. During development it will just say 'java' and that's OK.
                this += tk.createAboutMenuItem(APP_NAME, aboutStage)
                separator()
                item("Clear cache ...") {
                    setOnAction {
                        HistoryManager.clearCache()
                        Alert(Alert.AlertType.INFORMATION, "Cache has been cleared. Apps will re-download next time they are " +
                                "invoked or a background update occurs.", ButtonType.CLOSE).showAndWait()
                    }
                }
                separator()
                this += tk.createQuitMenuItem(APP_NAME)
            }
            tk.setApplicationMenu(appMenu);
        }
    }

    private fun onNavigate(text: String) {
        if (text.isBlank()) return

        // Parse what the user entered as if it were a command line: this feature is a bit of an easter egg,
        // but makes testing a lot easier, e.g. to force a re-download just put --clear-cache at the front.
        val options = GravitonCLI.parse(text)

        // These callbacks will run on the FX event thread.
        val events = object : CodeFetcher.Events {
            override suspend fun onStartedDownloading(name: String) {
                downloadProgress.set(0.0)
                isDownloading.set(true)
                messageText1.set("Please wait ...")
                messageText2.set("Resolving")
            }

            override suspend fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {
                val pr = totalDownloadedSoFar.toDouble() / totalBytesToDownload.toDouble()
                downloadProgress.set(pr)
                messageText1.set("DOWNLOADING")
                messageText2.set(name)
            }

            override suspend fun onStoppedDownloading() {
                downloadProgress.set(1.0)
                isDownloading.set(false)
                messageText1.set("")
                messageText2.set("")
            }
        }

        // Capture the output of the program and redirect it to a text area. In future we'll switch this to be a real
        // terminal and get rid of it for graphical apps.
        outputArea.text = ""
        val printStream = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                Platform.runLater {
                    outputArea.text += b.toChar()
                }
            }
        }, true)

        // Now start a coroutine that will run everything on the FX thread other than background tasks.
        launch(JavaFx) {
            try {
                AppLauncher(options, historyManager, primaryStage, JavaFx, events, printStream, printStream).start()
            } catch (e: Throwable) {
                onStartError(e)
            }
        }
    }

    private fun onStartError(e: Throwable) {
        // TODO: Handle errors much better than just splatting the exception name onto the screen!
        isDownloading.set(false)
        downloadProgress.set(0.0)
        messageText1.set("Start failed")
        val msg = if (e is AppLauncher.StartException) {
            if (e.rootCause is ArtifactNotFoundException) {
                "Could not locate the requested application"
            } else
                e.message
        } else
            e.toString()
        messageText2.set(msg)
        logger.error("Start failed", e)
    }

    private fun mockDownload() {
        isDownloading.set(true)
        downloadProgress.set(0.0)
        messageText1.set("Mock downloading ..")
        thread {
            Thread.sleep(5000)
            Platform.runLater {
                downloadProgress.animate(1.0, 5000.millis) {
                    setOnFinished {
                        isDownloading.set(false)
                        messageText1.set("")
                        messageText2.set("")
                    }
                }
            }
        }
    }
}

class Styles : Stylesheet() {
    companion object {
        val shellArea by cssclass()
        val content by cssclass()
        val logoText by cssclass()
        val messageBox by cssclass()
    }

    private val wireFont: Font = loadFont("/net/plan99/graviton/art/Wire One regular.ttf", 25.0)!!

    init {
        shellArea {
            fontFamily = "monospace"
            borderColor = multi(box(Color.gray(0.8, 1.0)))
            borderWidth = multi(box(3.px))
            borderRadius = multi(box(10.px))
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.8))
            scrollPane {
                content {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
                viewport {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
            }
        }

        logoText {
            font = wireFont
            fontSize = 120.pt
            effect = DropShadow(15.0, Color.WHITE)
        }

        messageBox {
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.9))
            backgroundRadius = multi(box(5.px))
            borderWidth = multi(box(3.px))
            borderColor = multi(box(Color.LIGHTGREY))
            borderRadius = multi(box(5.px))
        }
    }
}
