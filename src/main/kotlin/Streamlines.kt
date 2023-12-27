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
        defaultScene()

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

        //load tractogram from file
        val tractogram = tractogramGameObject(trx)
        val tractogramParent = RichNode()
        tractogramParent.addChild(tractogram)
        tractogramParent.name = "tractogram parent"
        container.addChild(tractogramParent)

        //load parcellation from file
        val parcellationObject = NiftiReader.niftiFromFile(parcellationPath, hub, csvPath)
        tractogramParent.addChild(parcellationObject) // outcommented to try the selection with two specific parcellation meshes: only those are supposed to show
        scene.spatial().updateWorld(true)

        // Load nifti volume from file
        val volume = NiftiReader.niftiFromFile(volumeDataset, hub)
        applyInvTractogramTransform(tractogram, volume)
        container.addChild(volume)

        //Test functions
        streamlineSelectionTransformedMesh(parcellationObject, tractogramParent) //current test function to try apply tranformation directly to the mesh in order to do a correct streamline selection
        //testStreamlineSelection(tractogramParent)
        //testStreamlineSelectionRealParcellation(tractogramParent, parcellationObject)
        //TODO: look through all "test" cases to check if all of them are needed and how to better write them
        //TODO: tractogram, volume and parcellation need to align

        // Try using a .tiff instead of the nifti to load the volume to try, if transformations might be correct, but the nifti format could be faulty
        //tryTiffVolume(container) //currently doesn't work: .tiff doesn't get shown, thus I don't know if it has the right transform
    }

    /**
     * Applies transformation that is stored in metadata of the tractogram. Since it doesn't work to apply the
     * transformation directly to the tractogram itself, the inverse transformation is applied to the volume instead.
     *
     * @param tractogram Scene object of the tractogram that contains the relevant metadata
     * @param volume Scene object of the volume that should be transformed instead of the tractogram
     * */
    private fun applyInvTractogramTransform(tractogram: RichNode, volume: Node) {
        val maxAcceptedDifference = 0.015f
        val transformTractogram = tractogram.metadata.get("voxelToRasMatrix") as Matrix4f
        val transformCopy = Matrix4f()
        transformCopy.set(transformTractogram)
        transformTractogram.invert()
        if (transformCopy.mul(transformTractogram).equals(Matrix4f().identity(), maxAcceptedDifference)) {
            logger.info("Inversion matrix and original matrix are inverse to each other with an acceptable error.")
        } else {
            logger.warn(
                "The inversion matrix and the original matrix are not inverse to each other with an acceptable error of max $maxAcceptedDifference per cell. " +
                        "The original transformation matrix needs to be applied to the tractogram instead of taking this route."
            )
        }
        // Prior to trying this, the transformation was applied to the tractogram directly, but this didn't work
        // TODO: Apply transformation directly to the tractogram (especially if warning gets displayed)

        /*
        Decomposing the matrix to get components that can directly be applied to the volume scene object.
        The following code can be deleted once there is a way to apply the transformation matrix as a whole to the volume.
        * */
        val invertedRotation = Quaternionf(0f, 0f, 0f, 0f)
        val invertedScaling = Vector3f(1f, 1f, 1f)
        val invertedTranslation = Vector3f(0f, 0f, 0f)
        transformTractogram.getScale(invertedScaling)
        transformTractogram.getUnnormalizedRotation(invertedRotation)
        transformTractogram.getTranslation(invertedTranslation)

        /*
        * Use the single components in order to compose a new matrix, that can be checked against the original matrix.
        * If their difference is greater than an acceptable error, the decomposition did not sufficiently work
        * (probably due to shearing which is not supported by the decomposition)
        * */
        val invScalingMatrix = Matrix4f().scale(invertedScaling)
        val invRotationMatrix = Matrix4f().rotate(invertedRotation)
        val invTranslationMatrix = Matrix4f().translate(invertedTranslation)
        val composedMatrix = invScalingMatrix.mul(invRotationMatrix).mul(invTranslationMatrix)
        logger.info(
            "composed matrix is $composedMatrix. Inverted transformation of tractogram is $transformTractogram." +
                    " Asserting that both are equal with maximal difference of $maxAcceptedDifference."
        )
        if (composedMatrix.equals(transformTractogram, maxAcceptedDifference)) {
            logger.info("Transformation components can be used one by one. Shearing can be neglected.")
        } else {
            logger.warn(
                "The shearing components aren't small enough to be neglected. " +
                        "Transformation needs to be applied by using the transformation matrix as a whole."
            )
        }

        /*
        * The transformation of the volume is composed of what was read from the volume metadata and is already applied
        * to the object (by functionality of class "NiftiReader", where the transformation is already applied using
        * specific factors) and the inverse transformation of the tractogram,
        * which additionally gets applied within this function.
        */
        // TODO: Combine information from both sources with sensible scaling factors (10, 100, 1000 maybe) (volume transformation and inverse tractogram transformation) in order to align tractogram (+parcellation) with the volume

        /*
        * The following factors can be used to explore which might be a combination of present information that leads
        * to an alignment of the tractogram (+parcellation) and the volume.
        * */
        val factorVolumeScale = 1f
        val factorTractogramScale = 1f
        val factorVolumeTranslation = 1f
        val factorTractogramTranslation = 0.1f

        volume.spatialOrNull()?.scale = volume.spatialOrNull()?.scale?.mul(factorVolumeScale)?.mul(invertedScaling.mul(factorTractogramScale)) ?: Vector3f(1f,1f,1f)
        volume.spatialOrNull()?.rotation = volume.spatialOrNull()?.rotation?.mul(invertedRotation) ?: Quaternionf(0f,0f,0f, 0f)
        volume.spatialOrNull()?.position = volume.spatialOrNull()?.position?.mul(factorVolumeTranslation)?.add(invertedTranslation.mul(factorTractogramTranslation)) ?: Vector3f(0f,0f,0f)

        // TODO: Apply Transformation directly as a matrix to the volume
        /*
        * Trials to apply the transformation matrix directly to the volume matrix, however this does not update
        * the actual transform yet, since this is rather defined by the sinlge components scaling, rotation and translation (position)
        * */
        /*scene.spatial().updateWorld(true)
        volume.spatialOrNull()?.world = volume.spatialOrNull()?.world?.mul(transformTractogram)  ?: Matrix4f().identity()
        volume.spatialOrNull()?.wantsComposeModel = true*/
    }

    private fun defaultScene() {
        val propertiesFile = File(this::class.java.simpleName + ".properties")
        if (propertiesFile.exists()) {
            val p = Properties()
            p.load(propertiesFile.inputStream())
            logger.info("Loaded properties from $propertiesFile:")
            p.forEach { k, v ->
                logger.info(" * $k=$v")
                System.setProperty(k as String, v as String)
            }
        }

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

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
     * Function is used to test, if transforming the parcellation mesh directly via its vertices leads to a correct
     * overlay of parcellation and tractogram, so that a streamline selection can be done.
     *
     * Idea: Transform vertices of the parcellation mesh by world transform and then transform
     * @param tractogramParent Scene object of the tractogram parent, which contains the tractogram
     * @param parcellationObject Scene object of the parcellation, which contains the meshes of the brain regions
     * */
    private fun streamlineSelectionTransformedMesh(parcellationObject: Node, tractogramParent: RichNode) { //TODO: Null-Management if we get empty streamline list: print warning message and then just don't display streamlines
        parcellationObject.spatialOrNull()?.updateWorld(true)
        parcellationObject.children.forEach { child ->
            child.visible = false
        }

        //TODO: Use the following to check if modelTransform contains exactly rotation, translation, scaling, nothing more
        /*logger.info("Local translation of parcellation is $localTranslationParcellation.")

        val localRotationParcellation = parcellationObject.spatialOrNull()?.rotation ?: run {
            logger.warn("Local rotation of parcellation is null. Applying identity matrix to the vertices instead.")
            Quaternionf(0f, 0f, 0f, 0f)
        }
        logger.info("Local rotation of parcellation is $localRotationParcellation.")
        val localScaleParcellation = parcellationObject.spatialOrNull()?.scale ?: run {
            logger.warn("Local scale of parcellation is null. Applying identity matrix to the vertices instead.")
            Vector3f(1f, 1f, 1f)
        }
        logger.info("Local scale of parcellation is $localScaleParcellation.")
        val localTransformParcellation = parcellationObject.spatialOrNull()?.model
        logger.info("Local transform of parcellation is $localTransformParcellation.")

        val translationMatrix = Matrix4f().translate(localTranslationParcellation)
        val rotationMatrix = Matrix4f().rotate(localRotationParcellation)
        val scaleMatrix = Matrix4f().scale(localScaleParcellation)
        val calculatedModelParcellation = translationMatrix.mul(rotationMatrix).mul(scaleMatrix)
        logger.info("Calculated local transform of parcellation is $calculatedModelParcellation.")*/

        //TODO: Use the following to test, if streamlines really aren't transformed in any way
        /*tractogramParent.children.get(0).children.forEach { streamline ->
            val spatial = streamline.spatialOrNull()
            val testMeshLocalTransform = spatial?.model
            val testMeshTranslation = spatial?.position
            val testMeshRotation = spatial?.rotation
            val testMeshScale = spatial?.scale
            if(testMeshLocalTransform!=Matrix4f().identity()||testMeshTranslation!=Vector3f(0f,0f,0f)||testMeshRotation!=Quaternionf(0f,0f,0f,1f)||testMeshScale!=Vector3f(1f,1f,1f)){
                logger.info("Local transform of streamline ${streamline.name} is $testMeshLocalTransform.")
                logger.info("Local translation of streamline ${streamline.name} is $testMeshTranslation.")
                logger.info("Local rotation of streamline ${streamline.name} is $testMeshRotation.")
                logger.info("Local scale of streamline ${streamline.name} is $testMeshScale.")
            }
        }*/

        logger.info("tractogram is transformed by local matrix: ${tractogramParent.children.get(0).spatialOrNull()?.model}.")

        /* //TODO: Use this to check if correct
        val calculatedWorldTransformParcellation = testMeshSpecificTransform.mul(localTransformParcellation)
        logger.info("Calculated world transform of parcellation is $calculatedWorldTransformParcellation.")*/

        val testMesh = parcellationObject.children[42] as Mesh
        //testMesh.spatial().position = testMesh.spatial().position.add(Vector3f(2f,0f,0f)) //test purposes: transform so that there are definetely streamlines in the area
        testMesh.spatial().updateWorld(true)
        val localParcellationTransform = parcellationObject.spatialOrNull()?.model ?: run {
            logger.warn("Model transform of test mesh is null. Applying identity matrix to the vertices instead.")
            Matrix4f().identity()
        }

        val localMeshTransform = testMesh.spatial().model

        logger.info("Model transform of parcellation is $localParcellationTransform.")
        logger.info("Model transform of testMesh is $localMeshTransform")
        logger.info("World transform of test mesh is ${testMesh.spatialOrNull()?.world}.")
        logger.info("World transform of parcellation parent is ${parcellationObject.parent?.spatialOrNull()?.world}.")
        val verticesBuffer = testMesh.geometry().vertices
        logger.info("There are ${verticesBuffer.capacity()/3} vertices in the test mesh.")
        logger.info("Vertex 0 is ${verticesBuffer.get(0)}, ${verticesBuffer.get(1)}, ${verticesBuffer.get(2)}.")
        val calculatedWorldValueVertex0 = testMesh.spatialOrNull()?.world?.transform(Vector4f(verticesBuffer.get(0), verticesBuffer.get(1), verticesBuffer.get(2), 1f))
        logger.info("Calculated transformed vertex 0 before mesh transformation is $calculatedWorldValueVertex0.")
        verticesBuffer.rewind()
        while(verticesBuffer.remaining() >= 3){
            //multiply each vertex with local transform
            val currentPos = verticesBuffer.position()
            val currentVertex = Vector4f(verticesBuffer.get(), verticesBuffer.get(), verticesBuffer.get(), 1f)
            localMeshTransform.transform(currentVertex)
            localParcellationTransform.transform(currentVertex)
            verticesBuffer.put(currentPos, currentVertex.x)
            verticesBuffer.put(currentPos+1, currentVertex.y)
            verticesBuffer.put(currentPos+2, currentVertex.z)
            verticesBuffer.position(currentPos+3)
        }
        logger.info("After applying the local transform, vertex 0 is ${verticesBuffer.get(0)}, ${verticesBuffer.get(1)}, ${verticesBuffer.get(2)}.")
        testMesh.spatial().position = Vector3f(0f,0f,0f)
        testMesh.spatial().scale = Vector3f(1f,1f,1f)
        testMesh.spatial().rotation = Quaternionf(0f,0f,0f,1f)

        parcellationObject.spatialOrNull()?.position = Vector3f(0f,0f,0f)
        parcellationObject.spatialOrNull()?.scale = Vector3f(1f,1f,1f)
        parcellationObject.spatialOrNull()?.rotation = Quaternionf(0f,0f,0f,1f)
        testMesh.geometry().dirty = true
        scene.spatial().updateWorld(true)
        logger.info("After applying all identity transforms, model of parcellation object is ${parcellationObject.spatialOrNull()?.model}.")
        verticesBuffer.rewind()

        logger.info("world transform after using identity transforms is ${testMesh.spatial().world}.")
        val calculatedWorldValueVertex0After = testMesh.spatialOrNull()?.world?.transform(Vector4f(verticesBuffer.get(0), verticesBuffer.get(1), verticesBuffer.get(2), 1f))
        logger.info("Calculated transformed vertex 0 after mesh transformation is $calculatedWorldValueVertex0After.")
        //val worldTransform = testMesh.spatial().world
        //logger.info("World transform of test mesh is $worldTransform.")
        testMesh.visible = true
        //TODO: check if verticesOfStreamlines contain the expected streamlines, since I changed some stuff around that
        logger.info("Vertices of streamlines contain ${verticesOfStreamlines.size} streamlines.")
        val selectedStreamlines = StreamlineSelector.preciseStreamlineSelection(testMesh, verticesOfStreamlines, localMeshTransform)

        logger.info("Bounding Box: ${testMesh.boundingBox.toString()} and translation of test mesh: ${testMesh.spatialOrNull()?.worldPosition()}.")
        tractogramParent.children.get(0).visible = false
        try {
            val selectedTractogram = displayableStreamlinesFromVerticesList(
                selectedStreamlines.shuffled()
                    .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>
            )
            tractogramParent.addChild(selectedTractogram)
        }catch (e: Exception){
            if(selectedStreamlines.isEmpty()){
                logger.warn("Empty list of streamlines. No streamline selection will be displayed.")
            }else{
                logger.warn("Exception occured: ${e.message}. No streamline selection will be displayed.")
            }
        }
        //parcellationObject.children[44].visible = true
    }

    private fun tryTiffVolume(container: RichNode) {
        //try to add volume as a .tif that was previously created via Fiji
        val volumeTiffPath = System.getProperty("datasets") + "\\scenery_tractography_vis_cortex1_ushort.nii.tif"

        val volumeTiff = Volume.fromPath(Paths.get(volumeTiffPath), hub)
        volumeTiff.colormap = Colormap.get("grays")
        //volumeTiff.transferFunction = TransferFunction.ramp(0.01f, 5200f) //0.5f before
        /*val tf = TransferFunction("FlatAfter")
        tf.addControlPoint(0.0f, 0f)
        tf.addControlPoint(0.01f, 0f)
        tf.addControlPoint(0.011f, 0.5f)
        tf.addControlPoint(1.0f, 0.5f)*/
        val tf = TransferFunction()
        tf.addControlPoint(0.0f, 0.0f) // for pixel value 0, alpha is 0 (completely transparent)
        tf.addControlPoint(0.01f/65535.0f, 0.0f) // for pixel value 100, alpha is 0 (completely transparent)
        tf.addControlPoint(1f / 65535.0f, 1.0f)
        tf.addControlPoint(1017.0f / 65535.0f, 1.0f) // for pixel value 1017, alpha is 1 (completely opaque)
        tf.addControlPoint(5001.0f / 65535.0f, 1.0f) // for pixel value 5001, alpha is 1 (completely opaque)
        tf.addControlPoint(3017.0f / 65535.0f, 1.0f)
        volumeTiff.transferFunction = tf

        volumeTiff.spatial().scale = Vector3f(
            0.43169886f * 100f,
            0.4339234f * 100f,
            0.6199905f * 100
        ) //normally volume is loaded with *100 scaling, tractogram would be loaded without any factor applied to the scaling
        volumeTiff.spatial().position =
            Vector3f(107.386f, -63.4674f, 119.598f).div(1000f) //for tractogram only divide by 10
        val x = -0.0049300706f
        val y = -0.9989844f
        val z = 0.04474579f
        val w = sqrt(1.0 - (x * x + y * y + z * z)).toFloat()
        volumeTiff.spatial().rotation = Quaternionf(x, y, z, w)
        container.addChild(volumeTiff)
    }

    private fun tractogramGameObject(trx: String): RichNode {
        // Load tractogram from file and add it to the scene
        val streamlinesAndTransform = tractogramFromFile(trx)
        verticesOfStreamlines = streamlinesAndTransform.streamlines
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
        tractogram.metadata.put("voxelToRasMatrix", streamlinesAndTransform.voxelToRasMatrix)
        return tractogram
    }

    data class TractogramData(val streamlines: ArrayList<ArrayList<Vector3f>>, val voxelToRasMatrix: Matrix4f)
    /**
     * Reads a .trx file given by a path String and creates a tractogram scene object that contains a random selection of streamlines
     *
     * @param path Path to the .trx file of the tractogram
     * @return Scene Object of the tractogram //TODO: Update
     * */
    fun tractogramFromFile(path: String): TractogramData{
        val trx = TRXReader.readTRX(path)
        val transform = Matrix4f(trx.header.voxelToRasMM)
        transform.transpose()

        logger.info("Transform of tractogram is: ${transform}.")

        val tempStreamlineList = ArrayList<ArrayList<Vector3f>>()
        trx.streamlines.forEachIndexed { index, line ->
            // if using a larger dataset, insert a shuffled().take(100) before the forEachIndexed
            val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size / 3)
            line.vertices.toList().windowed(3, 3) { p ->
                // X-axis is inverted compared to the NIFTi coordinate system
                val v = Vector3f(-p[0], p[1], p[2])
                if(v.length() > 0.001f) {
                    vecVerticesNotCentered.add(v)
                }
            }
            tempStreamlineList.add(vecVerticesNotCentered.map { it.mul(0.1f) } as ArrayList<Vector3f>) //transform tractogram, so the brain areas don't have to be "scaled" for streamline selection;
        }
        return TractogramData(tempStreamlineList, transform)
    }

    /**
     * Reads a parcellation from .nifti file and returns scene object containing a mesh for each label region
     *
     * @param parcellationPath path to .nifti file that contains the labelled parcellation represented by Integer values
     * @param csvPath path to .csv file containing the mapping between label number and region name
     * @return scene object containing meshes for each region
     * */
    /*fun parcellationFromFile(parcellationPath: String, csvPath: String): RichNode{
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
        parcellationContainer.spatial().position = (pm["translation"] as Vector3f).div(10.0f)
        parcellationContainer.spatial().scale = (pm["scale"] as Vector3f).div(100f)
        parcellationContainer.name = "Brain areas"

        return parcellationContainer
    }*/

    /**
     * Displays streamline selection that connects two specific given brain areas.
     * */
    /*fun testStreamlineSelection(tractogramContainer: RichNode){
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
        val selectedStreamlines = StreamlineSelector.preciseStreamlineSelection(testMesh, verticesOfStreamlines) //TODO: function needs transformation as a parameter

        val testMesh2 = parcellationMesh.children[42] as Mesh
        tractogramContainer.addChild(testMesh2)
        val selectedStreamlines2 = StreamlineSelector.preciseStreamlineSelection(testMesh2, selectedStreamlines as java.util.ArrayList<java.util.ArrayList<Vector3f>>) // TODO: function needs transformation matrix as parameter

        //val tractogram = RichNode()
        //TODO: handle an empty selectedStreamline list

        /*displayableStreamlinesFromVerticesList(selectedStreamlines2.shuffled()
            .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
            .forEach{ streamline -> tractogram.addChild(streamline)}*/
        val tractogram = displayableStreamlinesFromVerticesList(selectedStreamlines2.shuffled() //TODO: function needs transformation matrix as parameter
            .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
        testMesh2.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        testMesh.spatial().scale = Vector3f(0.1f,0.1f,0.1f)
        parcellationMesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        tractogramContainer.addChild(tractogram)
    }*/

    /**
     * Displays streamline selection that connects two specific given brain areas.
     * */
    fun testStreamlineSelectionRealParcellation(tractogramContainer: RichNode, parcellation: Node){
        parcellation.children.forEach {child ->
            child.materialOrNull()?.blending =
                Blending(transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                    destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha)
        }

        //Test case: only display the streamlines that start or end within the test mesh
        val testMesh = parcellation.children[44] as Mesh
        tractogramContainer.addChild(testMesh)
        //val selectedStreamlines = StreamlineSelector.preciseStreamlineSelection(testMesh, verticesOfStreamlines)

        val testMesh2 = parcellation.children[42] as Mesh
        tractogramContainer.addChild(testMesh2)
        //val selectedStreamlines2 = StreamlineSelector.preciseStreamlineSelection(testMesh2, selectedStreamlines as java.util.ArrayList<java.util.ArrayList<Vector3f>>)

        //val tractogram = RichNode()
        //TODO: handle an empty selectedStreamline list

        /*displayableStreamlinesFromVerticesList(selectedStreamlines2.shuffled()
            .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
            .forEach{ streamline -> tractogram.addChild(streamline)}*/
        //val tractogram = displayableStreamlinesFromVerticesList(selectedStreamlines2.shuffled()
        //    .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>)
        //testMesh2.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        //testMesh.spatial().scale = Vector3f(0.1f,0.1f,0.1f)
        //parcellation.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
        //tractogramContainer.addChild(tractogram)
    }

    /**
     * Reads nifti file to return a volume with correct transformations
     *
     * @param path to .nifti file that contains a volume
     * @return scenery volume
     * */
    //TODO: .nifti needs to be read in other places too, make method more general and merge with method that reads .nifti parcellation
    /*fun niftiVolumefromFile(path: String): Volume{
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
             pixeldim[0] = m["Voxel width"].toString().toFloat() *100 //What to do with the xyz units parameter? -> xyz_unity provides a code for the unit: in this case mm, but I don't know how to transfer this information to scenery: here scale factor *100 even though we have mm and want to translate to mm
             pixeldim[1] = m["Voxel height"].toString().toFloat() *100
             pixeldim[2] = m["Slice thickness"].toString().toFloat() *100
             logger.info("Pixeldim read from nifti is: ${pixeldim[0]}, ${pixeldim[1]}, ${pixeldim[2]}")

             val offset = Vector3f(
                 m["Quaternion x parameter"].toString().toFloat() * 1.0f / 1000,
                 m["Quaternion y parameter"].toString().toFloat() * -1.0f / 1000,
                 m["Quaternion z parameter"].toString().toFloat() * 1.0f / 1000,
             )
             logger.info("QOffset read from nifti is: $offset")

             //transformations that were given by the read metadata
             volume.spatial().rotation = quaternion
             volume.spatial().scale = Vector3f(pixeldim)
             logger.info("position previously is: "+ volume.spatial().position)
             volume.spatial().position = offset

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
    }*/

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
                val verticesDiff = vecVerticesNotCentered[i+1].minus(vecVerticesNotCentered[i])
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