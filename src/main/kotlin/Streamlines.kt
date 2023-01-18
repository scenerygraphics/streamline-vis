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
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import java.io.File
import java.nio.file.Paths
import java.util.Properties
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Visualizing streamlines with a basic data set.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class Streamlines: SceneryBase("No arms, no cookies", windowWidth = 1280, windowHeight = 720) {

    enum class ColorMode {
        LocalDirection,
        GlobalDirection
    }

    var colorMode = ColorMode.GlobalDirection
    override fun init() {
        val propertiesFile = File(this::class.java.simpleName + ".properties")
        if(propertiesFile.exists()) {
            val p = Properties()
            p.load(propertiesFile.inputStream())
            logger.info("Loaded properties from $propertiesFile:")
            p.forEach { k, v ->
                logger.info(" * $k=$v")
                System.setProperty(k as String, v as String)
            }
        }

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        val dataset = System.getProperty("dataset")
        val trx = System.getProperty("trx")
        val maximumStreamlineCount = System.getProperty("maxStreamlines", "2000").toInt()

        logger.info("Loading volume from $dataset and TRX tractogram from $trx, will show $maximumStreamlineCount streamlines max.")

        val container = RichNode()
        container.spatial().rotation = Quaternionf().rotationX(-PI.toFloat()/2.0f)
        scene.addChild(container)

        val volume = Volume.fromPath(Paths.get(dataset), hub)
        val m = volume.metadata

        //check if we have qform code: "Q-form Code" -> if it's bigger than 0, use method 2, if "S-form Code" is bigger than 0, use method 3
        //method 2 of NIfTI for reading
        logger.info("The following metadata is available:")
        m.forEach { (t, u) -> logger.info(" * $t -> $u")  }

        val transform = Matrix4f()
        if(m["Q-form Code"].toString().toFloat() > 0) { //method 2 of NIfTI for reading
            val x = m["Quaternion b parameter"].toString().toFloat()
            val y = m["Quaternion c parameter"].toString().toFloat()
            val z = m["Quaternion d parameter"].toString().toFloat()
            val w = sqrt(1.0-(x*x + y*y + z*z)).toFloat()
            val quaternion = Quaternionf(x, y, z, w)
            val axisAngle = AxisAngle4f()
            quaternion.get(axisAngle)
            logger.info("Rotation read from nifti is: $quaternion, Axis angle is $axisAngle")


            val pixeldim = floatArrayOf(0.0f, 0.0f, 0.0f) //should be the correct translation of dimensions to width/height/thickness, but if anything is weird with the scaling, check again
            pixeldim[0] = m["Voxel width"].toString().toFloat()*100 //What to do with the xyz units parameter? -> xyz_unity provides a code for the unit: in this case mm, but I don't know how to transfer this information to scenery: here scale factor *100 even though we have mm and want to translate to mm
            pixeldim[1] = m["Voxel height"].toString().toFloat()*100
            pixeldim[2] = m["Slice thickness"].toString().toFloat()*100
            logger.info("Pixeldim read from nifti is: ${pixeldim[0]}, ${pixeldim[1]}, ${pixeldim[2]}")

            val offset = Vector3f(
                m["Quaternion x parameter"].toString().toFloat() * 1.0f,
                m["Quaternion y parameter"].toString().toFloat() * 1.0f,
                m["Quaternion z parameter"].toString().toFloat() * 1.0f,
            )
            logger.info("QOffset read from nifti is: $offset")

            //transformations that were given by the read metadata
            volume.spatial().rotation = quaternion
            volume.spatial().scale = Vector3f(pixeldim)

        } else if (m["S-form Code"].toString().toFloat()>0) { //method 3 of NIfTI for reading
            for(i in 0..2){
                for(j in 0..3){
                    val coordinate: String = when(i){
                        0 -> "X"
                        1 -> "Y"
                        2 -> "Z"
                        else -> throw IllegalArgumentException()
                    }
                    val value = m["Affine transform $coordinate[$j]"]?.toString()?.toFloat() ?: throw NullPointerException()
                    transform.setRowColumn(i, j, value)
                }
            }
            transform.setRow(3, Vector4f(0F, 0F, 0F, 1F))
            //val matrix4ftransp = matrix4f.transpose() //transposing should not happen to this matrix, since translation is the last column -> column major
            logger.info("Affine transform read from nifti is: $transform")
            volume.spatial().wantsComposeModel = false
            volume.spatial().model = transform
        }

        volume.origin = Origin.Center
        volume.colormap = Colormap.get("grays")
        volume.transferFunction = TransferFunction.ramp(0.01f, 0.5f)

        container.addChild(volume)
        logger.info("transformation of nifti is ${volume.spatial().world}, Position is ${volume.spatial().worldPosition()}")

        val tractogram = RichNode()
        val trx1 = TRXReader.readTRX(trx)
        val scale = Vector3f()
        val translation = Vector3f()
        val quat = Quaternionf()
        val tr = Matrix4f(trx1.header.voxelToRasMM)
        tr.transpose()

        tr.getScale(scale)
        tr.getTranslation(translation)
        tr.getNormalizedRotation(quat)

        logger.info("Transform of tractogram is: ${tr.transpose()}. Scaling is $scale. Translation is $translation. Normalized rotation quaternion is $quat.")

        // if using a larger dataset, insert a shuffled().take(100) before the forEachIndexed
        trx1.streamlines.shuffled().take(maximumStreamlineCount).forEachIndexed { index, line ->
            val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size / 3)
            line.vertices.toList().windowed(3, 3) { p ->
                // X axis is inverted compared to the NIFTi coordinate system
                val v = Vector3f(-p[0], p[1], p[2])
                if(v.length() > 0.001f) {
                    vecVerticesNotCentered.add(v)
                }
            }

            val color = vecVerticesNotCentered.fold(Vector3f(0.0f)) { lhs, rhs -> (rhs - lhs).normalize() }

            val catmullRom = UniformBSpline(vecVerticesNotCentered, 10)
            val splineSize = catmullRom.splinePoints().size
            val geo = Curve(catmullRom, partitionAlongControlpoints = false) { triangle(splineSize) }
            geo.name = "Streamline #$index"
            geo.children.forEachIndexed { i, curveSegment ->
                val localColor = ((vecVerticesNotCentered.getOrNull(i+1) ?: Vector3f()) - (vecVerticesNotCentered.getOrNull(i) ?: Vector3f(0.0f))).normalize()
                curveSegment.materialOrNull()?.diffuse = when(colorMode) {
                    ColorMode.LocalDirection -> (localColor + Vector3f(0.5f)) / 2.0f
                    ColorMode.GlobalDirection -> color
                }
            }
            tractogram.addChild(geo)
        }

        tractogram.spatial().scale = scale * 0.1f
        tractogram.spatial().rotation = quat
        tractogram.spatial().position = Vector3f(0.0f, -translation.y/2.0f, translation.z) * 0.1f
        logger.info("transformation of tractogram is ${tractogram.spatial().world}, Position is ${tractogram.spatial().worldPosition()}, Scaling is ${tractogram.spatial().worldScale()}, Rotation is ${tractogram.spatial().worldRotation()}")
        container.addChild(tractogram)

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
            Streamlines().main()
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
