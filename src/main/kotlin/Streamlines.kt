import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.behaviours.SelectCommand
import graphics.scenery.geometry.Curve //Use with Justins old Code to display Streamlines
import graphics.scenery.geometry.UniformBSpline
//import graphics.scenery.geometry.curve.BaseShapesFromSingleShape //Use with Justins new Code to display Streamlines
//import graphics.scenery.geometry.curve.DefaultCurve //Use with Justins new Code to display Streamlines
import graphics.scenery.numerics.Random
import graphics.scenery.trx.TRXReader
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import net.imglib2.mesh.Meshes
import org.joml.*
import java.io.File
import java.nio.file.Paths
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.*


/**
 * Visualizing streamlines with a basic data set.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class Streamlines(maximumStreamlineCount: Int = 1000): SceneryBase("No arms, no cookies", windowWidth = 1280, windowHeight = 720) {

    enum class ColorMode {
        LocalDirection,
        GlobalDirection
    }

    var colorMode = ColorMode.GlobalDirection
    private var verticesOfStreamlines = ArrayList<ArrayList<Vector3f>>()
    var selectionVerticesOfStreamlines = ArrayList<ArrayList<Vector3f>>()
    var _maximumStreamlineCount: Int = maximumStreamlineCount
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

        // Example input to test code
        //TODO: Test folder with this data and test class
        val volumeDataset = System.getProperty("dataset") //in sciview user input, here test case: rather store in test-folder
        val trx = System.getProperty("trx") //in sciview user input, here test case: rather store in test-folder
        val parcellationPath = System.getProperty("parcellationPath")
        val csvPath = System.getProperty("csvPath")
        _maximumStreamlineCount = System.getProperty("maxStreamlines", "5000").toInt()

        logger.info("Loading volume from $volumeDataset and TRX tractogram from $trx, will show $_maximumStreamlineCount streamlines max.")

        val container = RichNode()
        container.spatial().rotation = Quaternionf().rotationX(-PI.toFloat()/2.0f)
        container.name = "brain parent"
        scene.addChild(container)

        // Load nifti volume from file
        val volume = niftiVolumefromFile(volumeDataset)
        container.addChild(volume)
        logger.info("transformation of volume nifti is ${volume.spatial().world}, Position is ${volume.spatial().worldPosition()}")
        val tractogram = tractogramGameObject(trx)
        
        val tractogramParent = RichNode()
        //tractogramParent.spatial().rotation = rotation //these transformations seem to not have any effect, but should be relevant since they're read from the .trx file
        //tractogramParent.spatial().position = Vector3f(0.0f, -translation.y/2.0f, translation.z) * 0.1f
        tractogramParent.addChild(tractogram)

        tractogramParent.name = "tractogram parent"
        container.addChild(tractogramParent)

        /*logger.info("transformation of tractogram is ${tractogram.spatial().world}, " +
                "Position is ${tractogram.spatial().worldPosition()}, " +
                "Scaling is ${tractogram.spatial().worldScale()}, " +
                "Rotation is ${tractogram.spatial().worldRotation()}")*/

        val parcellationObject = parcellationFromFile(parcellationPath, csvPath)
        tractogramParent.addChild(parcellationObject)

        //TODO: tractogram, volume and parcellation need to align

        // Test the selection: selects two specific brain regions for which a streamline selection is calculated
        //testStreamlineSelection(tractogramParent)

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

    private fun tractogramGameObject(trx: String): RichNode {
        // Load tractogram from file and add it to the scene
        val streamlinesAndTransform = tractogramFromFile(trx)
        verticesOfStreamlines = streamlinesAndTransform.streamlines
        val rotation = streamlinesAndTransform.rotation
        val translation = streamlinesAndTransform.translation
        selectionVerticesOfStreamlines = verticesOfStreamlines
        /*val tractogram = RichNode()
        tractogram.name = "tractogram"*/
        // Display random selection of all streamlines
        /*displayableStreamlinesFromVerticesList(
            verticesOfStreamlines.shuffled()
                .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>
        )
            .forEach { streamline -> tractogram.addChild(streamline) }*/
        val tractogram = displayableStreamlinesFromVerticesList(verticesOfStreamlines.shuffled().take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
        return tractogram
    }

    data class TractogramData(val streamlines: ArrayList<ArrayList<Vector3f>>, val rotation: Quaternionf, val translation: Vector3f)
    /**
     * Reads a .trx file given by a path String and creates a tractogram scene object that contains a random selection of streamlines
     *
     * @param path Path to the .trx file of the tractogram
     * @return Scene Object of the tractogram //TODO: Update
     * */
    fun tractogramFromFile(path: String): TractogramData{
        val scale = Vector3f()
        val translation = Vector3f()
        val quat = Quaternionf()

        val trx = TRXReader.readTRX(path)
        val tr = Matrix4f(trx.header.voxelToRasMM)
        tr.transpose()

        /*
        * Read transformations from .trx file and store them to be used to transform the game object
        * */
        tr.getScale(scale)
        tr.getTranslation(translation)
        tr.getNormalizedRotation(quat)

        logger.info("Transform of tractogram is: ${tr.transpose()}. Scaling is $scale. Translation is $translation. " +
                "Normalized rotation quaternion is $quat.")

        val tempStreamlineList = ArrayList<ArrayList<Vector3f>>()
        trx.streamlines.forEachIndexed { index, line ->
            // if using a larger dataset, insert a shuffled().take(100) before the forEachIndexed
            val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size / 3)
            line.vertices.toList().windowed(3, 3) { p ->
                // X axis is inverted compared to the NIFTi coordinate system
                val v = Vector3f(-p[0], p[1], p[2])
                if(v.length() > 0.001f) {
                    vecVerticesNotCentered.add(v)
                }
            }
            tempStreamlineList.add(vecVerticesNotCentered.map { it.mul(0.1f) } as ArrayList<Vector3f>) //transform tractogram, so the brain areas don't have to be "scaled" for streamline selection;
        }
        return TractogramData(tempStreamlineList, quat, translation) 
    }

    /**
     * Reads a parcellation from .nifti file and returns scene object containing a mesh for each label region
     *
     * @param parcellationPath path to .nifti file that contains the labelled parcellation represented by Integer values
     * @param csvPath path to .csv file containing the mapping between label number and region name
     * @return scene object containing meshes for each region
     * */
    fun parcellationFromFile(parcellationPath: String, csvPath: String): RichNode{
        val parcellationContainer = RichNode()
        val labelMap = ParcellationReader().readCsv(csvPath)
        val parcellationReader = ParcellationReader()
        val brainAreasList = ArrayList<String>()
        parcellationReader.loadParcellationAsLabelRegions(parcellationPath, csvPath).forEachIndexed { _, region ->
            // Generate mesh with imagej-ops
            val m = Meshes.marchingCubes(region);

            // Convert mesh into a scenery mesh for visualization
            val mesh = MeshConverter.toScenery(m, false, flipWindingOrder = true)
            mesh.name = labelMap.get(region.label)?.first ?: "undefined region"
            mesh.material().diffuse = Random.random3DVectorFromRange(0.2f, 0.8f)
            brainAreasList.add(mesh.name)

            // Scale, since all tractogram related files are scaled to fit into the window
            mesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
            // Add mesh to relevant container to be displayed in the scene
            parcellationContainer.addChild(mesh)
            // Add material to be visualized
            mesh.materialOrNull()?.blending =
                Blending(transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                    destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha)
        }

        parcellationContainer.metadata.put("brainAreas", brainAreasList)
        // Read transformation from parcellation Metadata and apply
        //TODO: this doesn't align the parcellation with the other objects, so find out why
        //TODO: Also check translation and scaling
        val pm = parcellationReader.parcellationMetadata
        val quaternion = Quaternionf(pm["rotx"].toString().toFloat(), pm["roty"].toString().toFloat(), pm["rotz"].toString().toFloat(), pm["rotw"].toString().toFloat())
        val axisAngle = AxisAngle4f()
        quaternion.get(axisAngle)
        parcellationContainer.spatial().rotation = quaternion
        parcellationContainer.name = "Brain areas"

        return parcellationContainer
    }

    /**
     * Displays streamline selection that connects two specific given brain areas.
     * */
    fun testStreamlineSelection(tractogramContainer: RichNode){
        /*
        * Read in parcellation meshes from the system. Set a name for the brain regions which can be used in the user
        * interaction to check, whether selected objects are brain regions. Set the brain regions to be transparent.
        * */
        val parcellationMesh = Mesh()
        parcellationMesh.readFrom(System.getProperty("parcellationMesh"))
        parcellationMesh.name = "Brain areas"

        parcellationMesh.children.forEach {child ->
            child.materialOrNull()?.blending =
                Blending(transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                    destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha)
        }

        //Test case: only display the streamlines that start or end within the test mesh
        val testMesh = parcellationMesh.children[44] as Mesh
        tractogramContainer.addChild(testMesh)
        val selectedStreamlines = StreamlineSelector.preciseStreamlineSelection(testMesh, verticesOfStreamlines)

        val testMesh2 = parcellationMesh.children[42] as Mesh
        tractogramContainer.addChild(testMesh2)
        val selectedStreamlines2 = StreamlineSelector.preciseStreamlineSelection(testMesh2, selectedStreamlines as java.util.ArrayList<java.util.ArrayList<Vector3f>>)

        //val tractogram = RichNode()
        //TODO: handle an empty selectedStreamline list

        /*displayableStreamlinesFromVerticesList(selectedStreamlines2.shuffled()
            .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
            .forEach{ streamline -> tractogram.addChild(streamline)}*/
        val tractogram = displayableStreamlinesFromVerticesList(selectedStreamlines2.shuffled()
            .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
        testMesh2.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        testMesh.spatial().scale = Vector3f(0.1f,0.1f,0.1f)
        parcellationMesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        tractogramContainer.addChild(tractogram)
    }

    /**
     * Reads nifti file to return a volume with correct transformations
     *
     * @param path to .nifti file that contains a volume
     * @return scenery volume
     * */
    //TODO: .nifti needs to be read in other places too, make method more general and merge with method that reads .nifti parcellation
    fun niftiVolumefromFile(path: String): Volume{
        val volume = Volume.fromPath(Paths.get(path), hub)
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
        return volume
    }

    /**
     * Calculates a curve representation for each streamline and determines a geometry that can be rendered.
     * In the process, metrics like fiber length and curvature of the streamlines are calculated and stored
     * into metadata.
     *
     * @param listVertices List of streamlines (which are lists of vertices / vector3f-points)
     * @return List of streamline scene-objects that can be rendered
     * */
    fun displayableStreamlinesFromVerticesList(listVertices: ArrayList<ArrayList<Vector3f>>) : RichNode {
        var timeStamp0 = 0.toLong()
        var timeStampSplineSize = 0.toLong()
        var timeStampGeo = 0.toLong()
        logger.info("Display of ${listVertices.size} streamlines")
        var maxLen = 0f
        var maxCurve = 0f
        var maxAvCurve = 0f
        val tractogram = RichNode()
        val streamlines = List<Node>(listVertices.size){index ->
            val vecVerticesNotCentered = listVertices[index]
            val color = vecVerticesNotCentered.fold(Vector3f(0.0f)) { lhs, rhs -> (rhs - lhs).normalize() }
            val catmullRom = UniformBSpline(vecVerticesNotCentered, 2)
            timeStamp0 = System.nanoTime()
            val splineSize = catmullRom.splinePoints().size
            timeStampSplineSize = System.nanoTime()
            val geo = Curve(catmullRom, partitionAlongControlpoints = false) { triangle(splineSize) } //Uses Justins old Code
            // Outcommented code uses code of scenery branch curve-restructuing
            /*val tri = listOf(
            Vector3f(0.1f, 0.1f, 0f).times(0.1f),
            Vector3f(0.1f, -0.1f, 0f).times(0.1f),
            Vector3f(-0.1f, -0.1f, 0f).times(0.1f),
            )
            val geo = DefaultCurve(catmullRom,
                { BaseShapesFromSingleShape.shapes(tri, catmullRom.splinePoints().size) })*/
            //TODO: SingleMeshCurve instead of DefaultCurve, if Streamlines are unicolor, because it's faster
            timeStampGeo = System.nanoTime()
            geo.name = "Streamline #$index"
            var lengthGeo = 0f
            var sumCurvature = 0f

            // Color for all curve segements will be the same if SingleMeshCurve is used
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

                // Outcommented since this can only be used with the curve-restructuring branch of scenery
                /*val diffTangent: Vector3f
                if (i<(streamlineSize-1)&&i>0) {
                    diffTangent = geo.frames[i+1].tangent - geo.frames[i-1].tangent
                    val derivTangent = diffTangent/(localLen + prevLocalLength)
                    val localCurvature = derivTangent.dot(geo.frames[i].normal).absoluteValue
                    sumCurvature += localCurvature
                    maxLocalCurve = max(maxLocalCurve, localCurvature)
                    minLocalCurve = min(minLocalCurve, localCurvature)
                }*/
                prevLocalLength = localLen
            }
            geo.metadata.put("length", lengthGeo)
            maxLen = max(maxLen, lengthGeo)
            geo.metadata.put("average curvature", sumCurvature/(streamlineSize-2))
            geo.metadata.put("maximum curvature", maxLocalCurve)
            geo.metadata.put("minimum curvature", minLocalCurve)
            maxAvCurve = max(maxAvCurve, sumCurvature/(streamlineSize-2))
            maxCurve = max(maxCurve, maxLocalCurve)
            tractogram.addChild(geo)
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
        tractogram.name = "tractogram"
        tractogram.metadata.put("maxLength", maxLen)

        return tractogram
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
                .filter { it.name == "tractogram" } [0].visible = false

            var selectedArea : HasSpatial? = null
            for (match in raycastResult.matches) {
                if(match.node.name.startsWith("grp")){
                    selectedArea = match.node as HasSpatial
                    break
                }
            }

            val timeStamp0 = System.nanoTime() / 1000000

            var streamlineSelection = StreamlineSelector.streamlineSelectionFromPolytope(selectedArea, selectionVerticesOfStreamlines)

            val timeStampSelection = System.nanoTime() / 1000000

            selectionVerticesOfStreamlines = streamlineSelection
            if(streamlineSelection.isNotEmpty()) streamlineSelection = streamlineSelection.shuffled()
                .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>
            //else scene.children.filter { it.name == "tractogram" } [0].visible = true //if no streamlines are available, it might be an idea to just show the whole brain again
            val tractogramReduced = RichNode()
            scene.children.filter { it.name == "brain parent" }[0].children
                .filter { it.name == "tractogram parent" } [0].addChild(tractogramReduced)

            val timeStamp0_2 = System.nanoTime() / 1000000

            /*val displayableStreamlines = displayableStreamlinesFromVerticesList(streamlineSelection)

            val timeStampGeometry = System.nanoTime() / 1000000

            displayableStreamlines.forEach{streamline -> tractogramReduced.addChild(streamline)}

            scene.children.filter { it.name == "brain parent" }[0].children
                .filter { it.name == "tractogram parent" } [0].removeChild("Reduced tractogram")
            tractogramReduced.name = "Reduced tractogram"
            logger.info("Time demand streamline selection: ${timeStampSelection-timeStamp0}, " +
                    "Time demand calculating geometry of streamlines: ${timeStampGeometry-timeStamp0_2}")*/
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
