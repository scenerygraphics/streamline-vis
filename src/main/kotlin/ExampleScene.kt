import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import org.joml.Vector3f
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

/**
 * Class sets up a default scene and tests functionality of TractogramTools and StreamlineSelector to be checked
 * visually.
 * */
class ExampleScene : SceneryBase("No arms, no cookies", windowWidth = 1280, windowHeight = 720) {
    private lateinit var tractogramTools : TractogramTools
    private lateinit var sceneComponents : TractogramTools.Components
    private lateinit var dataPath : String

    /**
     * Sets up the initial scene with test data and tests functionality of TractogramTools.
     * */
    override fun init() {
        defaultScene()
        dataPath = System.getProperty("datasets")

        testTractogramTools()
        //testStreamlineSelector()
    }

    override fun inputSetup() {
        super.inputSetup()
        setupCameraModeSwitching()
    }

    private fun testStreamlineSelector() {
        // Tests of Streamline Selector with test objects
        val firstMesh = datasetSetupStreamlineSelector()
        testPointCloud(firstMesh, scene)
        firstMesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
    }

    private fun datasetSetupStreamlineSelector(): Mesh {
        // The following parcellation mesh was created outside of Scenery
        val parcellationMeshPath = "$dataPath/scenery_tractography_vis_cortex_labels.nii.gz.obj"
        val parcellationMesh = Mesh()
        parcellationMesh.readFrom(parcellationMeshPath)
        val firstMesh = parcellationMesh.children[0] as Mesh
        firstMesh.materialOrNull().blending =
            Blending(
                transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
            )
        scene.addChild(firstMesh)
        return firstMesh
    }

    private fun testTractogramTools() {
        tractogramTools = TractogramTools(1000, this.scene, this.hub)
        setUpDatasetsTractogramTools()
        scene.addChild(sceneComponents.container)
//        streamlineSelectionTestTractogramTools()

        // Try using a .tiff instead of the nifti to load the volume
        // Could help to gather information about why transforming the nifti with its metadata does not align it with
        // the other scene objects
        //val tiffPath = "$dataPath\\scenery_tractography_vis_cortex1_ushort.nii.tif"
        //addTiffVolume(container) //currently doesn't work: .tiff doesn't get shown, thus I don't know if it has the right transform
    }

    /**
     * Tests streamline selection functionality of TractogramTools with two test meshes.
     * */
    private fun streamlineSelectionTestTractogramTools(){
        val meshes = ArrayList<Mesh>(2)
        meshes.add(sceneComponents.parcellationObject.children[4] as Mesh)
        logger.info("Calculating selection for mesh ${sceneComponents.parcellationObject.children[4].name}")
        meshes.add(sceneComponents.parcellationObject.children[44] as Mesh)
        logger.info("Calculating selection for mesh ${sceneComponents.parcellationObject.children[44].name}")
        val reducedTractogram = tractogramTools.streamlineSelectionTransformedMesh(sceneComponents.parcellationObject, sceneComponents.tractogram, meshes)
        sceneComponents.tractogramParent.addChild(reducedTractogram)
    }

    /**
     * Creates default scene with lightbox, lights, renderer and camera in which all other elements can be displayed.
     * */
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
     * Reads data path from System properties, holds information on where all datasets are located and loads them
     * with TractogramTools.
     * */
    private fun setUpDatasetsTractogramTools(){
        val volumeDataset =
            "$dataPath/scenery_tractography_vis_cortex1_ushort.nii.gz"
        val trx = "$dataPath/scenery_tractography_vis_wholebrain_newreference.trx"
        val parcellationPath = "$dataPath/scenery_tractography_vis_cortex_labels.nii.gz"
        val csvPath = "$dataPath/ctab_lhrh_vol.csv"

        sceneComponents = tractogramTools.setUp(trx, parcellationPath, csvPath, volumeDataset)
    }

    /**
     * Tests the function preciseStreamlineSelection of class StreamlineSelector with an example mesh and 100,000 sample points
     * Can show spheres where points are selected, so it can be used to check which points are selected visually;
     * */
    private fun testPointCloud(mesh : Mesh, container : Node) {
        val parentContainer = RichNode()
        container.addChild(parentContainer)

        val pointList = ArrayList<ArrayList<Vector3f>>()
        for (i in 0..9999) {
            val x = (40 * Math.random()).toFloat()-3
            val y = (90 * Math.random()).toFloat()-82
            val z = (70 * Math.random()).toFloat()+20
            val point = Vector3f(x, y, z)
            pointList.add(arrayListOf(point))
        }

        val streamlineSelection = StreamlineSelector.preciseStreamlineSelection(mesh, pointList)
        streamlineSelection.forEach {singlePointList ->
            val sphere = Sphere()
            sphere.spatial().position = singlePointList[0]
            sphere.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
            sphere.material().wireframe = true
            parentContainer.addChild(sphere) }

        parentContainer.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
    }



    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ExampleScene().main()
        }
    }

}