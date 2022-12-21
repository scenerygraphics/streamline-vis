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
import org.scijava.io.location.FileLocation
import java.nio.file.Paths
import kotlin.math.sqrt

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
        val reader = Volume.scifio.initializer().initializeReader(FileLocation(Paths.get("../../Datasets/tractography/scenery_tractography_vis_cortex1_ushort.nii.gz").toFile()))
        val metadata = reader.metadata
        val metadatatable = reader.metadata.table
        val volume = Volume.fromPath(Paths.get("../../Datasets/tractography/scenery_tractography_vis_cortex1_ushort.nii.gz"), hub)

        //check if we have qform code: "Q-form Code" -> if it's bigger than 0, use method 2, if "S-form Code" is bigger than 0, use method 3
        //method 2 of NIfTI for reading
        var matrix4f = Matrix4f()
        if(metadatatable.get("Q-form Code").toString().toFloat() > 0) { //method 2 of NIfTI for reading
            val quatb = metadatatable.get("Quaternion b parameter").toString().toFloat()
            val quatc = metadatatable.get("Quaternion c parameter").toString().toFloat()
            val quatd = metadatatable.get("Quaternion d parameter").toString().toFloat()
            val quata = sqrt(1.0-(quatb*quatb+quatc*quatc+quatd*quatd)).toFloat()
            val quaternion = Quaternionf(quatb, quatc, quatd, quata)
            val axisAngle = AxisAngle4f()
            quaternion.get(axisAngle)
            logger.info("Rotation read from nifti is: ${quata}, ${quatb}, ${quatc}, ${quatd}, Axis angle is ${axisAngle}")


            val pixeldim = FloatArray(3) {i -> 0F} //should be the correct translation of dimensions to width/height/thickness, but if anything is weird with the scaling, check again
            pixeldim[0] = metadatatable.get("Voxel width").toString().toFloat()*100 //What to do with the xyz units parameter? -> xyz_unity provides a code for the unit: in this case mm, but I don't know how to transfer this information to scenery: here scale factor *100 even though we have mm and want to translate to mm
            pixeldim[1] = metadatatable.get("Voxel height").toString().toFloat()*100
            pixeldim[2] = metadatatable.get("Slice thickness").toString().toFloat()*100
            logger.info("Pixeldim read from nifti is: ${pixeldim[0]}, ${pixeldim[1]}, ${pixeldim[2]}")

            val qoffsetx = metadatatable.get("Quaternion x parameter").toString().toFloat()
            val qoffsety = metadatatable.get("Quaternion y parameter").toString().toFloat()
            val qoffsetz = metadatatable.get("Quaternion z parameter").toString().toFloat()
            logger.info("QOffset read from nifti is: ${qoffsetx}, ${qoffsety}, ${qoffsetz}")

            //transformations that were given by the read metadata
            volume.spatial().rotation = Quaternionf(quatb, quatc, quatd, quata)
            //volume.spatial().position = Vector3f(qoffsetx, qoffsety, qoffsetz)
            volume.spatial().scale = Vector3f(pixeldim)

        }else if (metadatatable.get("S-form Code").toString().toFloat()>0) { //method 3 of NIfTI for reading
            for(i in 0..2){
                for(j in 0..3){
                    var coordinate : String
                    when(i){
                        0 -> coordinate = "X"
                        1 -> coordinate = "Y"
                        2 -> coordinate = "Z"
                        else -> throw IllegalArgumentException()
                    }
                    val value = metadatatable.get("Affine transform " + coordinate + "[" + j + "]")?.toString()?.toFloat() ?: throw NullPointerException()
                    matrix4f.setRowColumn(i, j, value)
                }
            }
            matrix4f.setRow(3, Vector4f(0F, 0F, 0F, 1F))
            //val matrix4ftransp = matrix4f.transpose() //transposing should not happen to this matrix, since translation is the last column -> column major
            logger.info("Affine transform read from nifti is: ${matrix4f}")
            volume.spatial().wantsComposeModel = false
            volume.spatial().world = matrix4f
        }

        volume.origin = Origin.Center //works better than if we use bottom fron left as an origin
        volume.transferFunction = TransferFunction.ramp(0.01f, 0.5f)
        //manual transformation which aligns the two objects (tractogram and volume) approximately
        volume.spatial().rotation = Quaternionf().rotationX(Math.PI.toFloat()/2)
        volume.spatial().move(floatArrayOf(0.5F,3.5F, -4.5F))

        scene.addChild(volume)
        logger.info("transformation of nifti is ${volume.spatial().world}, Position is ${volume.spatial().worldPosition()}")

        val tractogram = RichNode()
        val trx1 = TRXReader.readTRX("../../Datasets/tractography/scenery_tractography_vis_wholebrain_newreference.trx")
        var scale = Vector3f(0f,0f,0f)
        var translation = Vector3f(0f, 0f, 0f)
        var quat = Quaternionf(0f, 0f, 0f, 0f)
        val aangle = AxisAngle4f()
        logger.info("Transform of tractogram is: ${trx1.header.voxelToRasMM.transpose()}. Scaling is ${trx1.header.voxelToRasMM.getScale(scale)}. Translation is ${trx1.header.voxelToRasMM.getTranslation(translation)}. Normalized rotation quaternion is ${trx1.header.voxelToRasMM.getNormalizedRotation(quat).get(aangle)}.")

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

            val catmullRom = UniformBSpline(vecVerticesNotCentered, 10)
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
        logger.info("transformation of tractogram is ${tractogram.spatial().world}, Position is ${tractogram.spatial().worldPosition()}, Scaling is ${tractogram.spatial().worldScale()}, Rotation is ${tractogram.spatial().worldRotation()}")
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
