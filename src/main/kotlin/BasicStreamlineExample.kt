import com.esotericsoftware.minlog.Log
import org.joml.*
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.geometry.Curve
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.geometry.UniformBSpline
import graphics.scenery.trx.TRXReader
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import net.imglib2.KDTree
import net.imglib2.RealPoint
import net.imglib2.algorithm.kdtree.ClipConvexPolytopeKDTree
import net.imglib2.algorithm.kdtree.ConvexPolytope
import net.imglib2.algorithm.kdtree.HyperPlane
import java.lang.Math.sqrt
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

    fun streamlineSelectionFromPolytope(selectedArea: HasSpatial?, streamlines: ArrayList<ArrayList<Vector3f>>): ArrayList<ArrayList<Vector3f>>{
        //calculations of the hyperplane distances (second argument): normal vector *(point product) point(here translation+extend of the bounding box/normal vector length (here 1 or -1)
        val timeStamp0 = System.nanoTime() / 1000000
        val cubePos = selectedArea?.spatial()?.position ?: throw NullPointerException()
        val boundingBox = selectedArea.boundingBox ?: throw NullPointerException()
        val hyperplane1 = HyperPlane(0.0,0.0,-1.0, boundingBox.max.z.toDouble().plus(cubePos.z).times(-1))
        val hyperplane2 = HyperPlane(-1.0,0.0,0.0, boundingBox.max.x.toDouble().plus(cubePos.x).times(-1))
        val hyperplane3 = HyperPlane(0.0,0.0,1.0, boundingBox.min.z.toDouble().plus(cubePos.z))
        val hyperplane4 = HyperPlane(1.0,0.0,0.0, boundingBox.min.x.toDouble().plus(cubePos.x))
        val hyperplane5 = HyperPlane(0.0, -1.0,0.0, boundingBox.max.y.toDouble().plus(cubePos.y).times(-1))
        val hyperplane6 = HyperPlane(0.0, 1.0,0.0, boundingBox.min.y.toDouble().plus(cubePos.y))
        val polytope = ConvexPolytope(hyperplane1, hyperplane2, hyperplane3, hyperplane4, hyperplane5, hyperplane6)
        val timeStampPolytope = System.nanoTime() / 1000000

        val localKDTree : KDTree<Vector2i>
        if(streamlines == verticesOfStreamlines){
            localKDTree = globalKDTree
        }else{
            localKDTree = createKDTree(streamlines)
        }
        val timeStampKDTree = System.nanoTime() / 1000000

        val streamlineSelection = ArrayList<ArrayList<Vector3f>>()
        val clipkdtree = ClipConvexPolytopeKDTree(localKDTree)
        clipkdtree.clip(polytope)

        clipkdtree.insideNodes.forEach { node ->
            val index = node.get().x
            streamlineSelection.add(streamlines[index])
        }
        logger.info("Streamline selection contains ${streamlineSelection.size} streamlines")
        val timeStampClipTree = System.nanoTime() / 1000000
        logger.info("Time polytope: ${timeStampPolytope-timeStamp0}, Time kdTree: ${timeStampKDTree-timeStampPolytope}, Time clipTree: ${timeStampClipTree-timeStampKDTree}")

        return streamlineSelection
    }

    fun createKDTree(streamlines: ArrayList<ArrayList<Vector3f>>) : KDTree<Vector2i>{
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
        return KDTree(valuesList, positionsList)
    }

    var colorMode = ColorMode.GlobalDirection
    val verticesOfStreamlines = ArrayList<ArrayList<Vector3f>>()
    var selectionVerticesOfStreamlines = ArrayList<ArrayList<Vector3f>>()
    lateinit var globalKDTree : KDTree<Vector2i>
    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))
        val dataset = System.getProperty("dataset")
        val trx = System.getProperty("trx")
        val parcellation = System.getProperty("parcels")

        /*val parcel7 = System.getProperty("parcel7")
        val parcelMesh7 = Mesh()
        parcelMesh7.readFrom(parcel7)
        parcelMesh7.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        parcelMesh7.name = "Brain area"
        scene.addChild(parcelMesh7)*/

        val parcellationMesh = Mesh()
        parcellationMesh.readFrom("C:\\Users\\EllaHirche\\OneDrive\\Desktop\\Arbeit\\Datasets\\tractography\\scenery_tractography_vis_cortex_labels.nii.gz.obj")
        parcellationMesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        parcellationMesh.name = "Brain area"
        parcellationMesh.spatial().rotation = Quaternionf().rotationX(-Math.PI.toFloat()/2)
        parcellationMesh.children.forEach {child ->
            child.materialOrNull()?.blending = Blending(transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                    destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha)  // print status, if there is no blending
            val material = child.materialOrNull()
            material
        }
        //parcellationMesh.material().
        scene.addChild(parcellationMesh)


        //the following outcommented code loads the nifti from file; Not needed in this example for streamline selection
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

        //adds boxes that are used to model bounding boxes: The streamlines get selected according to which end / begin within these boxes
        /*val cube = Box(Vector3f(8f, 8f, 8f))
        cube.spatial().position = Vector3f(3.8f, 7.1f, -4.5f)
        cube.name = "Brain area"
        scene.addChild(cube)

        val cube2 = Box(Vector3f(6f, 6f, 6f))
        cube2.spatial().position = Vector3f(0f,-3f,0f)
        cube2.name = "Brain area"
        scene.addChild(cube2)*/

        val tractogram = RichNode()
        val trx1 = TRXReader.readTRX(trx)
        val scale = Vector3f()
        val translation = Vector3f()
        val quat = Quaternionf()
        trx1.header.voxelToRasMM.getScale(scale)
        trx1.header.voxelToRasMM.getTranslation(translation)
        trx1.header.voxelToRasMM.getNormalizedRotation(quat)

        logger.info("Transform of tractogram is: ${trx1.header.voxelToRasMM.transpose()}. Scaling is $scale. Translation is $translation. Normalized rotation quaternion is $quat.")

        trx1.streamlines.forEachIndexed { index, line ->
            val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size / 3)
            line.vertices.toList().windowed(3, 3) { p ->
                val v = Vector3f(p[0], p[2], p[1])
                if(v.length() > 0.001f) {
                    vecVerticesNotCentered.add(v)
                }
            }
            verticesOfStreamlines.add(vecVerticesNotCentered.map { it.mul(0.1f) } as ArrayList<Vector3f>) //transform tractogram, so the brain areas don't have to be "scaled" for streamline selection;
        }

        selectionVerticesOfStreamlines = verticesOfStreamlines
        displayableStreamlinesFromVerticesList(verticesOfStreamlines.shuffled().take(5000) as ArrayList<ArrayList<Vector3f>>).forEach{ streamline -> tractogram.addChild(streamline)}

        //tractogram.spatial().scale = Vector3f(0.1f)
        logger.info("transformation of tractogram is ${tractogram.spatial().world}, Position is ${tractogram.spatial().worldPosition()}, Scaling is ${tractogram.spatial().worldScale()}, Rotation is ${tractogram.spatial().worldRotation()}")
        scene.addChild(tractogram)
        tractogram.name = "Whole brain tractogram"

        globalKDTree = createKDTree(verticesOfStreamlines)

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

    fun displayableStreamlinesFromVerticesList(listVertices: ArrayList<ArrayList<Vector3f>>):List<Node> {
        var timeStamp0 = 0.toLong()
        var timeStampSplineSize = 0.toLong()
        var timeStampGeo = 0.toLong()
        logger.info("Display of ${listVertices.size} streamlines")
        val streamlines = List<Node>(listVertices.size){index ->
            val vecVerticesNotCentered = listVertices[index]
            val color = vecVerticesNotCentered.fold(Vector3f(0.0f)) { lhs, rhs -> (rhs - lhs).normalize() }
            val catmullRom = UniformBSpline(vecVerticesNotCentered, 2)
            timeStamp0 = System.nanoTime()
            val splineSize = catmullRom.splinePoints().size
            timeStampSplineSize = System.nanoTime()
            val geo = Curve(catmullRom, partitionAlongControlpoints = false) { triangle(splineSize) }
            timeStampGeo = System.nanoTime()
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
        logger.info("Time for splineSize: ${(timeStampSplineSize-timeStamp0)*listVertices.size/1000000}, Time for creating curve-geometry: ${(timeStampGeo-timeStampSplineSize)*listVertices.size/1000000}")
        return streamlines
    }

    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()

        val displayStreamlines: (Scene.RaycastResult, Int, Int) -> Unit = { raycastResult, i, i2 ->
            scene.children.filter { it.name == "Whole brain tractogram" } [0].visible = false

            var selectedArea : HasSpatial? = null
            for (match in raycastResult.matches) {
                if(match.node.name.startsWith("grp")){ //if(match.node.name == "Brain area"){
                    selectedArea = match.node as HasSpatial
                    break
                }
            }

            val timeStamp0 = System.nanoTime() / 1000000
            var streamlineSelection = streamlineSelectionFromPolytope(selectedArea, selectionVerticesOfStreamlines)
            val timeStampSelection = System.nanoTime() / 1000000
            selectionVerticesOfStreamlines = streamlineSelection
            if(streamlineSelection.isNotEmpty()) streamlineSelection = streamlineSelection.shuffled().take(5000) as ArrayList<ArrayList<Vector3f>> //else scene.children.filter { it.name == "Whole brain tractogram" } [0].visible = true //if no streamlines are available, it might be an idea to just show the whole brain again
            val tractogramReduced = RichNode()
            scene.addChild(tractogramReduced)
            val timeStamp0_2 = System.nanoTime() / 1000000
            val displayableStreamlines = displayableStreamlinesFromVerticesList(streamlineSelection)
            val timeStampGeometry = System.nanoTime() / 1000000

            displayableStreamlines.forEach{streamline -> tractogramReduced.addChild(streamline)}
            //tractogramReduced.spatial().scale = Vector3f(0.1f)

            scene.removeChild("Reduced tractogram")
            tractogramReduced.name = "Reduced tractogram"
            logger.info("Time demand streamline selection: ${timeStampSelection-timeStamp0}, Time demand calculating geometry of streamlines: ${timeStampGeometry-timeStamp0_2}")
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
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            BasicStreamlineExample().main()
        }

        val baseList = listOf(
            Vector3f(0.1f, 0.1f, 0f).times(0.1f),
            Vector3f(0.1f, -0.1f, 0f).times(0.1f),
            Vector3f(-0.1f, -0.1f, 0f).times(0.1f),
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
