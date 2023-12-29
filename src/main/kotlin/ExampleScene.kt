import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import org.joml.Vector3f
import java.io.File
import java.util.*

class ExampleScene : SceneryBase("No arms, no cookies", windowWidth = 1280, windowHeight = 720) {
    private lateinit var streams : Streamlines
    private lateinit var sceneComponents : Streamlines.Components

    /**
     * Sets up the initial scene and reads all relevant parameters from the configuration.
     * The initial scene in particular contains a tractogram, read from a .trx file, a parcellation,
     * read from an .obj file, which holds meshes of relevant brain regions and a volume,
     * read from a .nifti file, which shows the whole brain.
     * */
    override fun init() {
        streams = Streamlines(1000, this.scene, this.hub)
        defaultScene()
        setUpDatasets()
        streamlineSelectionTest()

        // Try using a .tiff instead of the nifti to load the volume to try, if transformations might be correct, but the nifti format could be faulty
        //tryTiffVolume(container) //currently doesn't work: .tiff doesn't get shown, thus I don't know if it has the right transform
    }

    private fun streamlineSelectionTest(){
        val meshes = ArrayList<Mesh>(2)
        meshes.add(sceneComponents.parcellationObject.children[4] as Mesh)
        meshes.add(sceneComponents.parcellationObject.children[44] as Mesh)
        streams.streamlineSelectionTransformedMesh(sceneComponents.parcellationObject, sceneComponents.tractogram, meshes)
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

    private fun setUpDatasets(){
        val dataPath = System.getProperty("datasets")
        val volumeDataset =
            "$dataPath\\scenery_tractography_vis_cortex1_ushort.nii.gz"
        val trx = "$dataPath\\scenery_tractography_vis_wholebrain_newreference.trx"
        val parcellationPath = "$dataPath\\scenery_tractography_vis_cortex_labels.nii.gz"
        val csvPath = "$dataPath\\ctab_lhrh_vol.csv"

        sceneComponents = streams.setUp(trx, parcellationPath, csvPath, volumeDataset)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ExampleScene().main()
        }
    }

}