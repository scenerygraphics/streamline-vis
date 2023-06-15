//import graphics.scenery.geometry.Curve //can only be used when using the old curve code

import com.esotericsoftware.minlog.Log
import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.geometry.UniformBSpline
import graphics.scenery.geometry.curve.CurveSingleShape
import graphics.scenery.numerics.Random
import graphics.scenery.primitives.Line
import graphics.scenery.trx.TRXReader
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import net.imglib2.KDTree
import net.imglib2.Localizable
import net.imglib2.RealPoint
import net.imglib2.algorithm.kdtree.ClipConvexPolytopeKDTree
import net.imglib2.algorithm.kdtree.ConvexPolytope
import net.imglib2.algorithm.kdtree.HyperPlane
import net.imglib2.mesh.alg.MeshCursor
import net.imglib2.mesh.obj.nio.BufferMesh
import net.imglib2.position.FunctionRandomAccessible
import net.imglib2.type.logic.BitType
import org.joml.*
import java.io.File
import java.io.IOException
import java.lang.Math
import java.util.*
import java.util.function.BiConsumer
import kotlin.math.*


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

    /**
     * Determines Streamlines that start or end within the Bounding box of a given 3-polytope
     *
     * @param selectedArea Node that holds the 3-Polytope which defines the area in which Streamlines have to start of finish to be selected
     * @param streamlines All streamlines from which should be selected
     * @return List of selected Streamlines (List of List of Points in a single streamline) that start or finish in the given polytope
     * */
    fun streamlineSelectionFromPolytope(selectedArea: HasSpatial?, streamlines: ArrayList<ArrayList<Vector3f>>): ArrayList<ArrayList<Vector3f>>{
        //TODO: Get rid of the Hyperplane-calculation as soon as there is a more precise way of determining the selected area / streamlines to be selected
        /*
        * Calculation of the Hyperplanes that form the bounding box.
        * The Hyperplanes form the polytope which is input to the following algorithm.
        * */
        // calculations of the hyperplane distances (second argument):
        // normal vector *(point product) point
        // here: translation+extend of the bounding box/normal vector length (here: 1 or -1)
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

        /*
        * Create KD-tree as the data structure holding all points where streamlines start or end.
        * A new KD-tree only gets created, if the initial amount of streamlines already got reduced,
        * since an initial KD-tree gets created in init().
        * */
        val localKDTree : KDTree<Vector2i>
        if(streamlines == verticesOfStreamlines){
            localKDTree = globalKDTree
        }else{
            localKDTree = createKDTree(streamlines)
        }

        val timeStampKDTree = System.nanoTime() / 1000000

        /*
        * Create and use data structure to determine inside points of a polytope.
        * The resulting points are translated back to the streamlines, which are returned as a list of vertices.
        * */
        val streamlineSelection = ArrayList<ArrayList<Vector3f>>()
        val clipkdtree = ClipConvexPolytopeKDTree(localKDTree)
        clipkdtree.clip(polytope)

        clipkdtree.insideNodes.forEach { node ->
            val index = node.get().x
            streamlineSelection.add(streamlines[index])
        }

        logger.info("Streamline selection contains ${streamlineSelection.size} streamlines.")
        val timeStampClipTree = System.nanoTime() / 1000000
        logger.info("Time polytope: ${timeStampPolytope-timeStamp0}, Time kdTree: ${timeStampKDTree-timeStampPolytope}, Time clipTree: ${timeStampClipTree-timeStampKDTree}")

        return streamlineSelection
    }

    /**
     * Creates a imglib2 KDTree datastructure that only contains all starting and end points of a given streamline list.
     *
     * @param streamlines List of list of vertices contained in a single streamline.
     * Only the first and last vertex of every streamline is stored inside the KD-tree, because these are the points
     * which need to efficiently be searched.
     * @return KD-tree data structure holding all points where a streamline either starts or ends
    * */
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
    var maximumStreamlineCount = 1000
    lateinit var globalKDTree : KDTree<Vector2i>
    /**
     * Sets up the initial scene and reads all relevant parameters from the configuration.
     * The initial scene in particular contains a tractogram, read from a .trx file, a parcellation,
     * read from an .obj file, which holds meshes of relevant brain regions and a volume,
     * read from a .nifti file, which shows the whole brain.
     * */
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
        maximumStreamlineCount = System.getProperty("maxStreamlines", "5000").toInt()

        logger.info("Loading volume from $dataset and TRX tractogram from $trx, will show $maximumStreamlineCount streamlines max.")

        val container = RichNode()
        container.spatial().rotation = Quaternionf().rotationX(-PI.toFloat()/2.0f)
        container.name = "brain parent"
        scene.addChild(container)

        val tractogramContainer = RichNode()
        tractogramContainer.name = "tractogram parent"
        container.addChild(tractogramContainer)

        val parcellationMesh = Mesh()
        parcellationMesh.readFrom(System.getProperty("parcellationMesh"))
        parcellationMesh.name = "Brain areas"
        //var first = true
        parcellationMesh.children.forEach {child ->
            child.materialOrNull()?.blending =
                Blending(transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                    destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha)

            //use the first Mesh for tests of streamline selection in general, but especially trials of more precise selections
            /*child as Mesh
            val childMeshImageJ = MeshConverter.toImageJ(child) //perhaps try waterproofedness, Duplicates
            if(first){
                first = false
            }else{
                child.visible = false
            }*/
        }

        tractogramContainer.addChild(parcellationMesh)
        //val firstMesh = parcellationMesh.children[0] as Mesh
        //converson to imglib2 needs to be done, if streamline selection is done with functionalities of ImageJ
        //val testMesh = MeshConverter.toImageJ(firstMesh)
        //TODO: check watertightness and duplicates
        //testPointCloud(testMesh, tractogramContainer)
        parcellationMesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)

        //the following outcommented code loads the nifti from file; Not needed in this example for streamline selection
        /*val volume = Volume.fromPath(Paths.get(dataset), hub)
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
*/

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

        logger.info("Transform of tractogram is: ${tr.transpose()}. Scaling is $scale. Translation is $translation. " +
                "Normalized rotation quaternion is $quat.")


        trx1.streamlines.forEachIndexed { index, line ->
        // if using a larger dataset, insert a shuffled().take(100) before the forEachIndexed
            val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size / 3)
            line.vertices.toList().windowed(3, 3) { p ->
                // X axis is inverted compared to the NIFTi coordinate system
                val v = Vector3f(-p[0], p[1], p[2])
                if(v.length() > 0.001f) {
                    vecVerticesNotCentered.add(v)
                }
            }
            verticesOfStreamlines.add(vecVerticesNotCentered.map { it.mul(0.1f) } as ArrayList<Vector3f>) //transform tractogram, so the brain areas don't have to be "scaled" for streamline selection;
        }

        selectionVerticesOfStreamlines = verticesOfStreamlines
        displayableStreamlinesFromVerticesList(verticesOfStreamlines.shuffled()
            .take(maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
            .forEach{ streamline -> tractogram.addChild(streamline)}

        tractogramContainer.spatial().rotation = quat
        tractogramContainer.spatial().position = Vector3f(0.0f, -translation.y/2.0f, translation.z) * 0.1f
        logger.info("transformation of tractogram is ${tractogram.spatial().world}, " +
                "Position is ${tractogram.spatial().worldPosition()}, " +
                "Scaling is ${tractogram.spatial().worldScale()}, " +
                "Rotation is ${tractogram.spatial().worldRotation()}")
        tractogramContainer.addChild(tractogram)
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

    /**
     * Calculates a curve representation for each streamline and determines a geometry that can be rendered.
     * In the process, metrics like fiber length and curvature of the streamlines are calculated and stored
     * into metadata.
     *
     * @param listVertices List of streamlines (which are lists of vertices / vector3f-points)
     * @return List of streamline scene-objects that can be rendered
     * */
    fun displayableStreamlinesFromVerticesList(listVertices: ArrayList<ArrayList<Vector3f>>) : List<Node> {
        var timeStamp0 = 0.toLong()
        var timeStampSplineSize = 0.toLong()
        var timeStampGeo = 0.toLong()
        logger.info("Display of ${listVertices.size} streamlines")
        var maxLen = 0f
        var maxCurve = 0f
        var maxAvCurve = 0f
        val streamlines = List<Node>(listVertices.size){index ->
            val vecVerticesNotCentered = listVertices[index]

            //display of streamlines with lines is currently not possible, since it's even less efficient than creating curves
            /*val geo = Line(transparent = false)
            geo.addPoints(vecVerticesNotCentered)*/

            val color = vecVerticesNotCentered.fold(Vector3f(0.0f)) { lhs, rhs -> (rhs - lhs).normalize() }
            val catmullRom = UniformBSpline(vecVerticesNotCentered, 2)
            timeStamp0 = System.nanoTime()
            //val splineSize = catmullRom.splinePoints().size
            timeStampSplineSize = System.nanoTime()
            //val geo = Curve(catmullRom, partitionAlongControlpoints = false) { triangle(splineSize) }
            //the following outcommented code can be used for the new CurveSingleShape-Code of curve_restructuring code
            val tri = listOf(
            Vector3f(0.1f, 0.1f, 0f).times(0.1f),
            Vector3f(0.1f, -0.1f, 0f).times(0.1f),
            Vector3f(-0.1f, -0.1f, 0f).times(0.1f),
            )
            val geo = CurveSingleShape(catmullRom, partitionAlongControlpoints = false, baseShape = tri)
            timeStampGeo = System.nanoTime()
            geo.name = "Streamline #$index"
            var lengthGeo = 0f
            var sumCurvature = 0f

            //there is only one curveSegment per Streamline if CurveSingleShape is used,
            // thus local color isn't actually local anymore; Filler until we implement color conventions
            geo.children.forEachIndexed { i, curveSegment ->
                val verticesDiff = vecVerticesNotCentered[i+1].minus(vecVerticesNotCentered[i] ?: Vector3f(0.0f))
                val localColor = (verticesDiff).normalize()
                curveSegment.materialOrNull()?.diffuse = when(colorMode) {
                    ColorMode.LocalDirection -> (localColor.plus(Vector3f(0.5f))) / 2.0f
                    ColorMode.GlobalDirection -> color
                }
            }

            val streamlineSize = vecVerticesNotCentered.size
            var prevLocalLength = 0f
            var minLocalCurve = 10000f
            var maxLocalCurve = 0f
            vecVerticesNotCentered.forEachIndexed{i, vertex ->
                val localLen : Float
                if (i<(streamlineSize-1)) localLen = vecVerticesNotCentered[i+1].distance(vecVerticesNotCentered[i]) else localLen = 0f
                lengthGeo += localLen

                val diffTangent: Vector3f
                if (i<(streamlineSize-1)&&i>0) {
                    diffTangent = geo.frames[i+1].tangent - geo.frames[i-1].tangent
                    val derivTangent = diffTangent/(localLen + prevLocalLength)
                    val localCurvature = derivTangent.dot(geo.frames[i].normal).absoluteValue
                    sumCurvature += localCurvature
                    maxLocalCurve = max(maxLocalCurve, localCurvature)
                    minLocalCurve = min(minLocalCurve, localCurvature)
                }
                prevLocalLength = localLen
            }
            geo.metadata.put("length", lengthGeo)
            maxLen = max(maxLen, lengthGeo)
            geo.metadata.put("average curvature", sumCurvature/(streamlineSize-2))
            geo.metadata.put("maximum curvature", maxLocalCurve)
            geo.metadata.put("minimum curvature", minLocalCurve)
            maxAvCurve = max(maxAvCurve, sumCurvature/(streamlineSize-2))
            maxCurve = max(maxCurve, maxLocalCurve)
            geo
        }

        /*val maxCurvatureFilter = 0.05f
        streamlines.forEachIndexed{i, streamline ->
            var curve = streamline.metadata.get("maximum curvature")
            var curveFloat = curve.toString().toFloat()
            if(curveFloat>maxCurvatureFilter){
                streamline.visible = false
            }
        }*/
        logger.info("Maximum curvature is $maxCurve, maximum average curve is $maxAvCurve Maximum Fiber length is $maxLen")
        logger.info("Time for splineSize: ${(timeStampSplineSize-timeStamp0)*listVertices.size/1000000}, " +
                "Time for creating curve-geometry: ${(timeStampGeo-timeStampSplineSize)*listVertices.size/1000000}")
        return streamlines
    }

    /**
     * Handles the input: If there is a double click on mesh / brain region, a streamline selection is done and
     * rendered instead of a whole tractogram
     * */
    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()

        /*
        * Defines the method which gets called as soon as there is a double click.
        * The closest brainregion to the click event will get selected and thus serves as the streamline selection criteria.
        * */
        val displayStreamlines: (Scene.RaycastResult, Int, Int) -> Unit = { raycastResult, i, i2 ->
            scene.children.filter { it.name == "brain parent" }[0].children
                .filter { it.name == "tractogram parent" } [0].children
                .filter { it.name == "Whole brain tractogram" } [0].visible = false

            var selectedArea : HasSpatial? = null
            for (match in raycastResult.matches) {
                if(match.node.name.startsWith("grp")){
                    selectedArea = match.node as HasSpatial
                    break
                }
            }

            val timeStamp0 = System.nanoTime() / 1000000

            var streamlineSelection = streamlineSelectionFromPolytope(selectedArea, selectionVerticesOfStreamlines)

            val timeStampSelection = System.nanoTime() / 1000000

            selectionVerticesOfStreamlines = streamlineSelection
            if(streamlineSelection.isNotEmpty()) streamlineSelection = streamlineSelection.shuffled()
                .take(maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>
            //else scene.children.filter { it.name == "Whole brain tractogram" } [0].visible = true //if no streamlines are available, it might be an idea to just show the whole brain again
            val tractogramReduced = RichNode()
            scene.children.filter { it.name == "brain parent" }[0].children
                .filter { it.name == "tractogram parent" } [0].addChild(tractogramReduced)

            val timeStamp0_2 = System.nanoTime() / 1000000

            val displayableStreamlines = displayableStreamlinesFromVerticesList(streamlineSelection)

            val timeStampGeometry = System.nanoTime() / 1000000

            displayableStreamlines.forEach{streamline -> tractogramReduced.addChild(streamline)}

            scene.children.filter { it.name == "brain parent" }[0].children
                .filter { it.name == "tractogram parent" } [0].removeChild("Reduced tractogram")
            tractogramReduced.name = "Reduced tractogram"
            logger.info("Time demand streamline selection: ${timeStampSelection-timeStamp0}, " +
                    "Time demand calculating geometry of streamlines: ${timeStampGeometry-timeStamp0_2}")
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
            Streamlines().main()
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
