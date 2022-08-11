import org.joml.*
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.geometry.Curve
import graphics.scenery.numerics.Random
import graphics.scenery.attribute.material.Material
import graphics.scenery.geometry.UniformBSpline
import graphics.scenery.proteins.Axis
import graphics.scenery.trx.TRXReader
import graphics.scenery.utils.extensions.minus
/**
 * Visualizing streamlines with a basic data set.
 *
 * @author  Justin Buerger <burger@mpi-cbg.de>
 */
class BasicStreamlineExample: SceneryBase("CurveCatmullRomExample", windowWidth = 1280, windowHeight = 720) {

    override fun init() {

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val rowSize = 10f

        val trx1 = TRXReader.readTRXfromStream(this.javaClass.getResource("dpsv.trx").openStream())
        trx1.streamlines.forEachIndexed { index, line ->
            if(index%10 ==0) {
                fun triangle(splineVerticesCount: Int): ArrayList<ArrayList<Vector3f>> {
                    val shapeList = ArrayList<ArrayList<Vector3f>>(splineVerticesCount)
                    for (i in 0 until splineVerticesCount) {
                        val list = ArrayList<Vector3f>()
                        list.add(Vector3f(0.1f, 0.1f, 0f))
                        list.add(Vector3f(0.1f, -0.1f, 0f))
                        list.add(Vector3f(-0.1f, -0.1f, 0f))
                        shapeList.add(list)
                    }
                    return shapeList
                }

                val vecVerticesNotCentered = ArrayList<Vector3f>(line.vertices.size % 3)
                for (i in 0 until line.vertices.size step 3) {
                    if (i%3 == 0 && i+2 < line.vertices.size) {
                        vecVerticesNotCentered.add(Vector3f(line.vertices[i], line.vertices[i+1], line.vertices[i+2]))
                    }
                }
                val centroid = Axis.getCentroid(vecVerticesNotCentered)
                val vecVertices = vecVerticesNotCentered.map { it.minus(centroid) }
                val catmullRom = UniformBSpline(vecVertices as ArrayList<Vector3f>)
                val splineSize = catmullRom.splinePoints().size
                val geo = Curve(catmullRom) { triangle(splineSize) }
                geo.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
                scene.addChild(geo)
            }
        }



        val lightbox = Box(Vector3f(75.0f, 75.0f, 75.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material {
            diffuse = Vector3f(0.1f, 0.1f, 0.1f)
            roughness = 1.0f
            metallic = 0.0f
            cullingMode = Material.CullingMode.None
        }
        scene.addChild(lightbox)
        val lights = (0 until 20).map {
            val l = PointLight(radius = 25.0f)
            l.spatial {
                position = Vector3f(
                    Random.randomFromRange(-rowSize / 2.0f, rowSize / 2.0f),
                    Random.randomFromRange(-rowSize / 2.0f, rowSize / 2.0f),
                    Random.randomFromRange(1.0f, 5.0f)
                )
            }
            l.emissionColor = Random.random3DVectorFromRange(0.2f, 0.8f)
            l.intensity = Random.randomFromRange(0.2f, 0.8f)

            lightbox.addChild(l)
            l
        }
        lights.forEach { scene.addChild(it) }

        val stageLight = PointLight(radius = 200.0f)
        stageLight.name = "StageLight"
        stageLight.intensity = 0.5f
        stageLight.spatial {
            position = Vector3f(0.0f, 0.0f, 5.0f)
        }
        scene.addChild(stageLight)

        val cameraLight = PointLight(radius = 5.0f)
        cameraLight.name = "CameraLight"
        cameraLight.emissionColor = Vector3f(1.0f, 1.0f, 0.0f)
        cameraLight.intensity = 0.8f

        val cam: Camera = DetachedHeadCamera()
        cam.spatial {
            position = Vector3f(0.0f, 0.0f, 10.0f)
        }
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        scene.addChild(cam)
        cam.addChild(cameraLight)
        val trx = TRXReader
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
    }
}
