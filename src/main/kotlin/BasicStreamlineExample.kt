import org.joml.*
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.geometry.Curve
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import graphics.scenery.geometry.UniformBSpline
import graphics.scenery.trx.TRXReader
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import java.nio.file.Paths

/**
 * Visualizing streamlines with a basic data set.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class BasicStreamlineExample: SceneryBase("No arms, no cookies", windowWidth = 1280, windowHeight = 720) {

    enum class ColorMode {
        LocalDirection,
        GlobalDirection
    }

    var colorMode = ColorMode.GlobalDirection
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        val volume = Volume.fromPath(Paths.get("../../Datasets/tractography/scenery_tractography_vis_cortex2.tif"), hub)
        volume.spatial().rotation.rotateX(Math.PI.toFloat()/2.0f)
        volume.origin = Origin.FrontBottomLeft
        volume.spatial().scale = Vector3f(50.0f)
        volume.transferFunction = TransferFunction.ramp(0.1f, 0.5f)
        scene.addChild(volume)

        val tractogram = RichNode()
        val trx1 = TRXReader.readTRX("../../Datasets/tractography/scenery_tractography_vis_wholebrain_newreference.trx")
        logger.info("Transform is: ${trx1.header.voxelToRasMM}")
        // if using a larger dataset, insert a shuffled().take(100) before the forEachIndexed
        trx1.streamlines.shuffled().take(1000).forEachIndexed { index, line ->
            val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size / 3)
            line.vertices.toList().windowed(3, 3) { p ->
                val v = Vector3f(p[0], p[2], p[1])
                if(v.length() > 0.001f) {
                    vecVerticesNotCentered.add(v)
                }
            }

            val color = vecVerticesNotCentered.fold(Vector3f(0.0f)) { lhs, rhs -> (rhs - lhs).normalize() }

            val catmullRom = UniformBSpline(vecVerticesNotCentered)
            val splineSize = catmullRom.splinePoints().size
            val geo = Curve(catmullRom, partitionAlongControlpoints = false) { triangle(splineSize) }
            geo.name = "Streamline #$index"
            geo.children.forEachIndexed { i, curveSegment ->
                val localColor = (vecVerticesNotCentered[i+1] - (vecVerticesNotCentered[i] ?: Vector3f(0.0f))).normalize()
                curveSegment.materialOrNull()?.diffuse = when(colorMode) {
                    ColorMode.LocalDirection -> (localColor + Vector3f(0.5f)) / 2.0f
                    ColorMode.GlobalDirection -> color
                }
            }
            tractogram.addChild(geo)
        }

        tractogram.spatial().scale = Vector3f(0.1f)
        scene.addChild(tractogram)

        val lightbox = Box(Vector3f(75.0f, 75.0f, 75.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material {
            diffuse = Vector3f(0.1f, 0.1f, 0.1f)
            roughness = 1.0f
            metallic = 0.0f
            cullingMode = Material.CullingMode.None
        }
        scene.addChild(lightbox)
        val ambient = AmbientLight(intensity = 0.5f)
        scene.addChild(ambient)

        Light.createLightTetrahedron<PointLight>(spread = 2.0f, intensity = 5.0f)
            .forEach {
                it.emissionColor = Random.random3DVectorFromRange(0.2f, 0.8f)
                scene.addChild(it)
            }

        val cam: Camera = DetachedHeadCamera()
        cam.spatial {
            position = Vector3f(0.0f, 0.0f, 10.0f)
        }
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        scene.addChild(cam)
    }

    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            BasicStreamlineExample().main()
        }

        val baseList = listOf(
            Vector3f(0.1f, 0.1f, 0f),
            Vector3f(0.1f, -0.1f, 0f),
            Vector3f(-0.1f, -0.1f, 0f),
        )

        fun triangle(splineVerticesCount: Int): List<List<Vector3f>> {
            val shapeList = ArrayList<List<Vector3f>>(splineVerticesCount)
            for (i in 0 until splineVerticesCount) {
                shapeList.add(baseList)
            }
            return shapeList
        }
    }
}
