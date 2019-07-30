package rhmodding.bread.editor

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.event.EventHandler
import javafx.geometry.Pos
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.TextAlignment
import javafx.scene.transform.Affine
import javafx.scene.transform.Scale
import rhmodding.bread.Bread
import rhmodding.bread.model.*
import rhmodding.bread.util.em
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt


abstract class Editor<F : IDataModel>(val app: Bread, val dataFile: File, val data: F, val texture: BufferedImage)
    : BorderPane() {
    
    val canvasPane: VBox = VBox().apply {
        styleClass += "vbox"
    }
    val zoomLabel: Label = Label("Zoom: 100%").apply {
        textAlignment = TextAlignment.RIGHT
    }
    val canvas: Canvas = Canvas(512.0, 512.0)
    var zoomFactor: Double = 1.0
        set(value) {
            field = value.coerceIn(0.25, 4.0)
            zoomLabel.text = "Zoom: ${(field * 100).roundToInt()}%"
        }
    val originLinesCheckbox: CheckBox = CheckBox("Show origin lines").apply {
        isSelected = true
    }
    val showGridCheckbox: CheckBox = CheckBox("Show grid").apply {
        isSelected = true
    }
    val darkGridCheckbox: CheckBox = CheckBox("Dark grid").apply {
        isSelected = false
        disableProperty().bind(Bindings.not(showGridCheckbox.selectedProperty()))
    }
    
    val splitPane: SplitPane = SplitPane()
    
    val sidebar: TabPane = TabPane().apply {
        this.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
    }
    abstract val spritesTab: SpritesTab<F>
    abstract val animationsTab: AnimationsTab<F>
    abstract val advPropsTab: AdvancedPropertiesTab<F>
    
    protected val subimageCache: WeakHashMap<Long, BufferedImage> = WeakHashMap()
    
    init {
        stylesheets += "style/editor.css"
        center = splitPane
        minWidth = 15.0.em
        
        canvasPane.children += canvas
        canvasPane.children += VBox().apply {
            styleClass += "vbox"
            alignment = Pos.CENTER_RIGHT
            children += HBox().apply {
                styleClass += "hbox"
                alignment = Pos.CENTER_RIGHT
                children += Button("Reset").apply {
                    setOnAction {
                        zoomFactor = 1.0
                        repaintCanvas()
                    }
                }
                children += zoomLabel
            }
            children += Label("Scroll the mouse wheel on the canvas to zoom in/out")
            children += HBox().apply {
                styleClass += "hbox"
                alignment = Pos.CENTER_LEFT
//                children += showGridCheckbox.apply {
//                    selectedProperty().addListener { _, _, _ ->
//                        repaintCanvas()
//                    }
//                }
                children += darkGridCheckbox.apply {
                    selectedProperty().addListener { _, _, _ ->
                        repaintCanvas()
                    }
                }
                children += originLinesCheckbox.apply {
                    selectedProperty().addListener { _, _, _ ->
                        repaintCanvas()
                    }
                }
            }
        }
        
        canvas.onScroll = EventHandler {
            if (it.deltaX > 0 || it.deltaY > 0) {
                zoomFactor *= 2.0.pow(1 / 8.0)
            } else {
                zoomFactor /= 2.0.pow(1 / 8.0)
            }
            repaintCanvas()
        }
        
        Platform.runLater {
            sidebar.tabs.addAll(spritesTab, animationsTab/*, advPropsTab*/)
        }
        sidebar.selectionModel.selectedItemProperty().addListener { _, _, t ->
            repaintCanvas()
            (animationsTab.stepSpriteSpinner.valueFactory as SpinnerValueFactory.IntegerSpinnerValueFactory).also {
                it.max = data.sprites.size - 1
                it.value = it.value.coerceAtMost(it.max)
            }
            if (t != animationsTab) {
                animationsTab.currentTimeline = null
            }
        }
        
        splitPane.items.addAll(sidebar, canvasPane)
        
        Platform.runLater {
            repaintCanvas()
        }
    }
    
    abstract fun saveData(file: File)
    
    fun repaintCanvas() {
        drawCheckerBackground()
        when (sidebar.selectionModel.selectedItem) {
            spritesTab -> {
                drawSprite(data.sprites[spritesTab.spriteSpinner.value], spritesTab.spritePartSpinner.value)
            }
            animationsTab -> {
                val stepIndex = if (animationsTab.currentTimeline != null) animationsTab.playbackStepProperty.value else animationsTab.aniStepSpinner.value
                val step = animationsTab.currentAnimation.steps.getOrNull(stepIndex)
                if (step != null) {
                    drawAnimationStep(step)
                }
            }
        }
    }
    
    fun drawCheckerBackground(canvas: Canvas = this.canvas,
                              showGrid: Boolean = showGridCheckbox.isSelected,
                              originLines: Boolean = originLinesCheckbox.isSelected,
                              darkGrid: Boolean = darkGridCheckbox.isSelected) {
        val g = canvas.graphicsContext2D
        g.clearRect(0.0, 0.0, canvas.width, canvas.height)
        
        if (showGrid) {
            g.save()
            g.transform(getZoomTransformation())
            val blockSize = 16.0
            val blockColorEven = if (darkGrid) Color.BLACK else Color.WHITE
            val blockColorOdd = if (darkGrid) Color.web("#353535FF") else Color.LIGHTGREY
            for (x in ((canvas.width / 2 - canvas.width / zoomFactor / 2.0) / blockSize - 2).toInt()..((canvas.width / 2 + canvas.width / zoomFactor / 2.0) / blockSize + 2).toInt()) {
                for (y in ((canvas.height / 2 - canvas.height / zoomFactor / 2.0) / blockSize - 2).toInt()..((canvas.height / 2 + canvas.height / zoomFactor / 2.0) / blockSize + 2).toInt()) {
                    if ((x + y) % 2 != 0) {
                        g.fill = blockColorOdd
                    } else {
                        g.fill = blockColorEven
                    }
                    g.fillRect(x * blockSize, y * blockSize, blockSize, blockSize)
                }
            }
            g.restore()
        }
        
        // Origin lines
        if (originLines) {
            val originLineWidth = 1.0
            val xAxis = if (darkGrid && showGrid) Color(0.5, 0.5, 1.0, 0.75) else Color(0.0, 0.0, 1.0, 0.75)
            val yAxis = if (darkGrid && showGrid) Color(1.0, 0.5, 0.5, 0.75) else Color(1.0, 0.0, 0.0, 0.75)
            g.fill = xAxis
            g.fillRect(0.0, canvas.height / 2 - originLineWidth / 2, canvas.width, originLineWidth)
            g.fill = yAxis
            g.fillRect(canvas.width / 2 - originLineWidth / 2, 0.0, originLineWidth, canvas.height)
        }
    }
    
    fun getZoomTransformation(zoomFactor: Double = this.zoomFactor, canvas: Canvas = this.canvas): Affine = Affine(Scale(zoomFactor, zoomFactor, canvas.width / 2, canvas.height / 2))
    
    open fun drawSprite(sprite: ISprite, selectedPart: Int = -1) {
        val g = canvas.graphicsContext2D
        val img = texture
        for (part in sprite.parts) {
            val subImg = part.createFXSubimage(img, getCachedSubimage(part), Color.WHITE)
            g.save()
            g.transform(getZoomTransformation())
            part.transform(canvas, g)
            g.drawImage(subImg, part.posX - canvas.width / 2, part.posY - canvas.height / 2)
            g.restore()
        }
        val part = sprite.parts.getOrNull(selectedPart)
        if (part != null) {
            g.save()
            g.transform(getZoomTransformation())
            part.transform(canvas, g)
            g.globalAlpha = 1.0
            g.stroke = Color.RED
            g.strokeRect(part.posX - canvas.width / 2, part.posY - canvas.height / 2, (part.regionW.toInt() * part.stretchX).absoluteValue * 1.0, (part.regionH.toInt() * part.stretchY).absoluteValue * 1.0)
            g.restore()
        }
    }
    
    open fun drawAnimationStep(step: IAnimationStep) {
        val g = canvas.graphicsContext2D
        val img = texture
        val sprite = data.sprites[step.spriteIndex.toInt()]
        for (part in sprite.parts) {
            val subImg = part.createFXSubimage(img, getCachedSubimage(part), Color.WHITE)
            g.save()
            g.transform(getZoomTransformation())
            g.globalAlpha = step.opacity.toInt() / 255.0
            g.transform(Affine(Scale(step.stretchX.toDouble(), step.stretchY.toDouble(), canvas.width / 2, canvas.height / 2)))
            part.transform(canvas, g)
            g.drawImage(subImg, part.posX - canvas.width / 2, part.posY - canvas.height / 2)
            g.restore()
        }
    }
    
    protected open fun getCachedSubimage(part: ISpritePart): BufferedImage {
        val key: Long = (part.regionX.toLong() shl 48) or (part.regionY.toLong() shl 32) or (part.regionW.toLong() shl 16) or (part.regionH.toLong())
        return subimageCache.getOrPut(key) {
            texture.getSubimage(part.regionX.toInt(), part.regionY.toInt(), part.regionW.toInt(), part.regionH.toInt())
        }
    }
    
    abstract fun addSprite(sprite: ISprite)
    abstract fun removeSprite(sprite: ISprite)
    abstract fun addSpritePart(sprite: ISprite, part: ISpritePart)
    abstract fun removeSpritePart(sprite: ISprite, part: ISpritePart)
    abstract fun addAnimation(animation: IAnimation)
    abstract fun removeAnimation(animation: IAnimation)
    abstract fun addAnimationStep(animation: IAnimation, animationStep: IAnimationStep)
    abstract fun removeAnimationStep(animation: IAnimation, animationStep: IAnimationStep)
    abstract fun createSprite(): ISprite
    abstract fun createSpritePart(): ISpritePart
    abstract fun createAnimation(): IAnimation
    abstract fun createAnimationStep(): IAnimationStep
    
}