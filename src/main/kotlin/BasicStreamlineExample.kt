import org.joml.*
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.geometry.Curve
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import graphics.scenery.controls.behaviours.MouseDragSphere
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.geometry.UniformBSpline
import graphics.scenery.trx.TRXReader
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import net.imglib2.AbstractLocalizable
import net.imglib2.KDTree
import net.imglib2.RealLocalizable
import net.imglib2.RealPoint
import net.imglib2.algorithm.kdtree.ClipConvexPolytopeKDTree
import net.imglib2.algorithm.kdtree.ConvexPolytope
import net.imglib2.algorithm.kdtree.HyperPlane
import org.apache.commons.math3.geometry.hull.ConvexHull
import org.apache.commons.math3.geometry.hull.ConvexHullGenerator
import org.apache.commons.math3.geometry.partitioning.Hyperplane
import java.nio.file.Paths
import java.util.Dictionary
import javax.vecmath.Point3f
import javax.vecmath.Tuple2f
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

    fun streamlineSelectionFromSphere(radius: Float, position: Vector3f, streamlines: ArrayList<ArrayList<Vector3f>>): ArrayList<ArrayList<Vector3f>>{
        //creation of the kd tree holding all the last and first points of the streamlines
        val listLength = streamlines.size*2
        val valuesList = List(listLength){ index ->
            val even = index % 2
            val index2 = ((index - even) / 2)
            Vector2i(index2, even)
        }
        val positionsList = List(listLength){ index ->
            val even = index % 2
            val index2 = ((index - even) / 2)
            val streamline = streamlines[index2]
            val streamlinepoint = if(even==0) streamline.first() else streamline.last()
            val position = RealPoint(streamlinepoint.x, streamlinepoint.y, streamlinepoint.z)
            position
        }
        val kdtree = KDTree(valuesList, positionsList)

        //val streamlineSelection = ArrayList<ArrayList<Vector3f>>()
        //val clipkdtree = ClipConvexPolytopeKDTree(kdtree)
        //clipkdtree.clip()
        //clipkdtree.insideNodes

        val streamlineSelection = ArrayList<ArrayList<Vector3f>>()
        streamlines.forEachIndexed{index, vertices ->
            val vecBegin = vertices.first()-position
            val vecEnd = vertices.last()-position
            if(vecBegin.length()<radius || vecEnd.length()<radius){
                streamlineSelection.add(vertices)
            }
        }
        return streamlineSelection
    }

    var colorMode = ColorMode.GlobalDirection
    val verticesOfStreamlines = ArrayList<ArrayList<Vector3f>>()
    lateinit var wholetractogram : Node
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        val dataset = System.getProperty("dataset")
        val trx = System.getProperty("trx")

        /*val volume = Volume.fromPath(Paths.get(dataset), hub)
        val m = volume.metadata

        //check if we have qform code: "Q-form Code" -> if it's bigger than 0, use method 2, if "S-form Code" is bigger than 0, use method 3
        //method 2 of NIfTI for reading
        var transform = Matrix4f()
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
            volume.spatial().position = offset/100.0f
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

//        volume.origin = Origin.Center //works better than if we use bottom fron left as an origin
        volume.colormap = Colormap.get("grays")
        volume.transferFunction = TransferFunction.ramp(0.01f, 0.5f)
        //manual transformation which aligns the two objects (tractogram and volume) approximately
        volume.spatial().rotation = Quaternionf().rotationX(Math.PI.toFloat()/2)
//        volume.spatial().move(floatArrayOf(0.5F,3.5F, -4.5F))

        scene.addChild(volume)
        logger.info("transformation of nifti is ${volume.spatial().world}, Position is ${volume.spatial().worldPosition()}")
*/
        val sphere = Sphere(3f, 20)
        sphere.spatial().position = Vector3f(0f,-3f,0f)
        scene.addChild(sphere)


        val sphere2 = Sphere(3f, 20)
        sphere2.spatial().position = Vector3f(3.8f,7.1f,-4.5f)
        scene.addChild(sphere2)

        val tractogram = RichNode()
        val trx1 = TRXReader.readTRX(trx)
        val scale = Vector3f()
        val translation = Vector3f()
        val quat = Quaternionf()
        trx1.header.voxelToRasMM.getScale(scale)
        trx1.header.voxelToRasMM.getTranslation(translation)
        trx1.header.voxelToRasMM.getNormalizedRotation(quat)

        logger.info("Transform of tractogram is: ${trx1.header.voxelToRasMM.transpose()}. Scaling is $scale. Translation is $translation. Normalized rotation quaternion is $quat.")
        // if using a larger dataset, insert a shuffled().take(100) before the forEachIndexed
        //trx1.streamlines.shuffled().take(1000).forEachIndexed { index, line ->
        /*val geoComplete = List<Node>(trx1.streamlines.size){ index ->
            val line = trx1.streamlines[index]*/

        trx1.streamlines.forEachIndexed { index, line ->
            val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size / 3)
            line.vertices.toList().windowed(3, 3) { p ->
                val v = Vector3f(p[0], p[2], p[1])
                if(v.length() > 0.001f) {
                    vecVerticesNotCentered.add(v)
                }
            }
            verticesOfStreamlines.add(vecVerticesNotCentered) //.map { it.mul(0.1f) } as ArrayList<Vector3f>) //transform tractogram and not have to worry about transforming the sphere-pos / sphere-radius plus not having to scale later on;
            /*val color = vecVerticesNotCentered.fold(Vector3f(0.0f)) { lhs, rhs -> (rhs - lhs).normalize() }

            val catmullRom = UniformBSpline(vecVerticesNotCentered, 10)
            val splineSize = catmullRom.splinePoints().size
            val geoSingle = Curve(catmullRom, partitionAlongControlpoints = false) { triangle(splineSize) }
            geoSingle.name = "Streamline #$index"
            geoSingle.children.forEachIndexed { i, curveSegment ->
                val localColor = (vecVerticesNotCentered[i+1] - (vecVerticesNotCentered[i] ?: Vector3f(0.0f))).normalize()
                curveSegment.materialOrNull()?.diffuse = when(colorMode) {
                    ColorMode.LocalDirection -> (localColor + Vector3f(0.5f)) / 2.0f
                    ColorMode.GlobalDirection -> color
                }
            }
            geoSingle*/
        }

        /*//val listVertices = streamlineSelectionFromSphere(sphere.radius, sphere.spatial().position, verticesOfStreamlines)
        val listVertices1 = streamlineSelectionFromSphere(30f, Vector3f(0f, -30f, 0f), verticesOfStreamlines)
        val listVertices = streamlineSelectionFromSphere(30f, Vector3f(38f,71f,-45f), listVertices1)*/

        //geoComplete.shuffled().take(1000).forEach{streamline -> tractogram.addChild(streamline)}

        val verticesOfStreamlinesSelection = verticesOfStreamlines.shuffled().take(1000) as ArrayList<ArrayList<Vector3f>>
        displayableStreamlinesFromVerticesList(verticesOfStreamlinesSelection).forEach{ streamline -> tractogram.addChild(streamline)}

        tractogram.spatial().scale = Vector3f(0.1f)
        logger.info("transformation of tractogram is ${tractogram.spatial().world}, Position is ${tractogram.spatial().worldPosition()}, Scaling is ${tractogram.spatial().worldScale()}, Rotation is ${tractogram.spatial().worldRotation()}")
        scene.addChild(tractogram)
        wholetractogram = tractogram

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

    fun displayableStreamlinesFromVerticesList(listVertices: ArrayList<ArrayList<Vector3f>>):List<Node> { //an der Stelle vielleicht schon alle Position-Lists durch 10 teilen? -> eigentlich schon vorher bei erstelleung Vertex-Liste
        val streamlines = List<Node>(listVertices.size){index ->
            val vecVerticesNotCentered = listVertices[index]
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
            geo
        }
        return streamlines
    }

    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()

        val displayStreamlines: (Scene.RaycastResult, Int, Int) -> Unit = { raycastResult, i, i2 ->
            wholetractogram.visible = false
            var pos = Vector3f(0f,0f,0f)
            /*raycastResult.matches.firstOrNull()?.let{nearest -> //it returns a point light instead of the sphere, even though the sphere is in the list with all the matches -> rather search specifically for a sphere
              pos = nearest.node.spatialOrNull()?.position?.mul(10f) ?: Vector3f(0f,0f,0f)
            } //what if it isn't a sphere? Also did not work, since other scene nodes came before the sphere*/

            //rather iterate through matches and see, whether they are spheres; This one didn't work, for it always said sphere to be of type Unit
            /*val sphere =
            raycastResult.matches.forEach { match ->
                if(match.node.name == "sphere") match.node as Sphere
            }*/
            var sphere = Sphere()
            raycastResult.matches.forEach { match ->
                if(match.node.name == "sphere"){
                    sphere = match.node as Sphere
                }
            }

            val spRadius = sphere.radius * 10
            val spPos = sphere.spatial().position.mul(10f)

            //only show those that are selected by the sphere; where do I get the info about the tractogram / streamlines that are required?
            var streamlineSelection = streamlineSelectionFromSphere(spRadius, spPos, verticesOfStreamlines)
            streamlineSelection = streamlineSelectionFromSphere(30f, Vector3f(38f,71f,-45f), streamlineSelection).shuffled().take(1000) as ArrayList<ArrayList<Vector3f>>
            val displayableStreamlines = displayableStreamlinesFromVerticesList(streamlineSelection)
            val tractogramReduced = RichNode()

            displayableStreamlines.forEach{streamline -> tractogramReduced.addChild(streamline)}
            tractogramReduced.spatial().scale = Vector3f(0.1f)
            scene.addChild(tractogramReduced)
        }
        renderer?.let { r ->
            inputHandler?.addBehaviour(
                "selectStreamlines", SelectCommand(
                    "selectStreamlines", r, scene, {
                        scene.findObserver()
                    }, action = displayStreamlines, debugRaycast = false
                ))
        }
        inputHandler?.addKeyBinding("selectStreamlines", "double-click button1")

        inputHandler?.addBehaviour(
            "dragObject", MouseDragSphere(
                "dragObject", {
                    scene.findObserver()
                }, debugRaycast = false
            )
        )
        inputHandler?.addKeyBinding("dragObject", "1")//make sure, that only spheres can be selected here
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
