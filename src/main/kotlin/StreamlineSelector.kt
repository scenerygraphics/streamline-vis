import com.esotericsoftware.minlog.Log
import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.extensions.times
import net.imglib2.KDTree
import net.imglib2.Localizable
import net.imglib2.RealPoint
import net.imglib2.algorithm.kdtree.ClipConvexPolytopeKDTree
import net.imglib2.algorithm.kdtree.ConvexPolytope
import net.imglib2.algorithm.kdtree.HyperPlane
//import net.imglib2.mesh.alg.InteriorPointTest //deprecated
import net.imglib2.mesh.alg.Interior
import org.joml.Matrix4f
import org.joml.Vector2i
import org.joml.Vector3f
import org.joml.Vector4f
import java.io.File
import java.io.IOException
import java.util.*
import java.util.function.BiConsumer
import kotlin.collections.ArrayList
import kotlin.math.min


/**
 * Provides functionality to select streamlines of a given List, by using meshes as the selection criteria.
 * Only streamlines that start or end within the given mesh are selected. A single Streamline is given by a list
 * of points.
 *
 * It needs to be assured, that both the mesh and streamline points are in the same coordinate system and space,
 * before using the functions.
 * */
class StreamlineSelector: SceneryBase("No arms, no cookies", windowWidth = 1280, windowHeight = 720)  {
    /**
     * Initializing function that is currently used to set up an example for the insideness test (testPointCloud1)
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

        val parcellationMesh = Mesh()
        parcellationMesh.readFrom(System.getProperty("parcellationMesh"))

        //Only used for testing purposes:
        /*val firstMesh = parcellationMesh.children[0] as Mesh
        firstMesh.materialOrNull()?.blending =
            Blending(transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha)
        scene.addChild(firstMesh)

        val testMesh = MeshConverter.toImageJ(firstMesh)
        //testPointCloud2(firstMesh, scene)
        testPointCloud1(testMesh, scene)
        //firstMesh.spatial().position = Vector3f(0f,0f,0f)
        firstMesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)*/

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
     * Tests the imgLib2-Mesh library for its mesh-insideness test with an example mesh and 100,000 sample points
     * Can show spheres where points are selected, so it can be used to check which points are selected visually;
     * Only works, when mesh is given in the configuration file
     * */
    //TODO: Use this to construct a test case outside of this class
    /*fun testPointCloud1(mesh : net.imglib2.mesh.Mesh, container : Node) {
        val points: MutableList<Vector3f> = ArrayList()
        val realPoints: MutableList<RealPoint> = ArrayList()
        val parentContainer = RichNode()
        container.addChild(parentContainer)
        for (i in 0..9999) {
            val x = (40 * Math.random()).toFloat()-3
            val y = (90 * Math.random()).toFloat()-82
            val z = (70 * Math.random()).toFloat()+20
            val point = Vector3f(x, y, z)
            val realPoint = RealPoint(point.x, point.y, point.z)
            realPoints.add(realPoint)
            points.add(point)
            /*val sphere = Sphere()
            sphere.spatial().position = Vector3f(x,y,z)
            sphere.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
            parentContainer.addChild(sphere)*/
        }

        val timeStamp0 = System.nanoTime()
        //val interiorPointTest = InteriorPointTest(mesh, 1.0) //deprecated
        val interiorPointTest = Interior(mesh, 1.0)

        /*points.forEach{point ->
            val point_real = RealPoint(point.x, point.y, point.z)
            if (interiorPointTest.isInside(point_real)) {
                val sphere = Sphere()
                sphere.spatial().position = Vector3f(point.x.toFloat(),point.y.toFloat(),point.z.toFloat())
                sphere.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
                sphere.material().wireframe = true
                parentContainer.addChild(sphere)
            }
        }*/
        realPoints.forEach{point ->
            if (interiorPointTest.isInside(point)) {
                val sphere = Sphere()
                sphere.spatial().position = Vector3f(point.getFloatPosition(0),point.getFloatPosition(1),point.getFloatPosition(2))
                sphere.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
                sphere.material().wireframe = true
                parentContainer.addChild(sphere)
            }
        }
        val timeStamp1 = System.nanoTime()
        Log.info("Time difference was: ${timeStamp1-timeStamp0}")
        parentContainer.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)

    }*/

    /**
     * Tests the function insidePoint of this class an example mesh and 100,000 sample points
     * Can show spheres where points are selected, so it can be used to check which points are selected visually;
     * Only works, when mesh is given in the configuration file
     * */
    //TODO: Use this to construct a test case outside of this class
    /*fun testPointCloud2(mesh : Mesh, container : Node) {
        val points: MutableList<Vector3f> = ArrayList()
        val realPoints: MutableList<RealPoint> = ArrayList()
        val parentContainer = RichNode()
        container.addChild(parentContainer)
        for (i in 0..9999) {
            val x = (40 * Math.random()).toFloat()-3
            val y = (90 * Math.random()).toFloat()-82
            val z = (70 * Math.random()).toFloat()+20
            val point = Vector3f(x, y, z)
            val realPoint = RealPoint(point.x, point.y, point.z)
            points.add(point)
            realPoints.add(realPoint)
            realPoints.add(realPoint)
        }
        val insideMask = insidePoints(mesh, realPoints)
        var streamlineSelection = realPoints.filterIndexed { index, _ ->
            insideMask.getOrNull(index/2) == true
        }
        streamlineSelection.forEach {point ->
            val sphere = Sphere()
            sphere.spatial().position = Vector3f(point.getFloatPosition(0),point.getFloatPosition(1),point.getFloatPosition(2))
            sphere.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
            sphere.material().wireframe = true
            parentContainer.addChild(sphere) }

        parentContainer.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)

    }*/


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            StreamlineSelector().main()
        }

        /**
         * Determines all streamlines that precisely start or end in a given mesh.
         * Make sure that the mesh and streamlines are in the same space, before using this method!
         *
         * @param mesh Scenery-Mesh that is used to select streamlines
         * @param streamlines List of all streamlines to be selected from
         * @return List of streamlines that got selected
         * */
        //fun preciseStreamlineSelection(mesh: Mesh, meshTransform: Matrix4f, streamlines : java.util.ArrayList<java.util.ArrayList<Vector3f>>, streamlineTransform: Matrix4f) : List<java.util.ArrayList<Vector3f>>{
        fun preciseStreamlineSelection(mesh: Mesh, streamlines : java.util.ArrayList<java.util.ArrayList<Vector3f>>, transform: Matrix4f) : List<java.util.ArrayList<Vector3f>>{
            val insideMask = insidePoints(mesh, startAndEndPointList(streamlines), transform)
            var streamlineSelection = streamlines.filterIndexed { index, _ ->
                insideMask.getOrNull(index) == true
            }
            //var streamlineSelection = streamlines.take(10) //Dummy-Line to give back a Streamline selection, that can be displayed without time consuming calcuation
            return streamlineSelection
        }

        /**
         * Determines Streamlines that start or end within the Bounding box of a given 3-polytope
         *
         * @param selectedArea Node that holds the 3-Polytope which defines the area in which Streamlines have to start of finish to be selected
         * @param streamlines All streamlines from which should be selected
         * @return List of selected Streamlines (List of List of Points in a single streamline) that start or finish in the given polytope
         * */
        //fun streamlineSelectionFromPolytope(selectedArea: HasSpatial?, transformSelectedArea: Matrix4f, streamlines: java.util.ArrayList<java.util.ArrayList<Vector3f>>, tranformStreamlines: Matrix4f): java.util.ArrayList<java.util.ArrayList<Vector3f>> {
        fun streamlineSelectionFromPolytope(selectedArea: HasSpatial?, streamlines: java.util.ArrayList<java.util.ArrayList<Vector3f>>): java.util.ArrayList<java.util.ArrayList<Vector3f>> { //TODO: transformations need to be applied to polytope before using in this function
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
            //TODO: Think of a new way to not always recreate the KD-tree: the global variables gloablKDTree and verticesOfStreamlines come from the Streamlines-Class
            /*if(streamlines == verticesOfStreamlines){
                localKDTree = globalKDTree
            }else{
                localKDTree = createKDTree(streamlines)
            }*/

            localKDTree = createKDTree(streamlines)

            val timeStampKDTree = System.nanoTime() / 1000000

            /*
            * Create and use data structure to determine inside points of a polytope.
            * The resulting points are translated back to the streamlines, which are returned as a list of vertices.
            * */
            val streamlineSelection = java.util.ArrayList<java.util.ArrayList<Vector3f>>()
            val clipkdtree = ClipConvexPolytopeKDTree(localKDTree)
            clipkdtree.clip(polytope)

            clipkdtree.insideNodes.forEach { node ->
                val index = node.get().x
                streamlineSelection.add(streamlines[index])
            }

            //logger.info("Streamline selection contains ${streamlineSelection.size} streamlines.")
            val timeStampClipTree = System.nanoTime() / 1000000
            //logger.info("Time polytope: ${timeStampPolytope-timeStamp0}, Time kdTree: ${timeStampKDTree-timeStampPolytope}, Time clipTree: ${timeStampClipTree-timeStampKDTree}")

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
        fun createKDTree(streamlines: java.util.ArrayList<java.util.ArrayList<Vector3f>>) : KDTree<Vector2i> {
            val listLength = streamlines.size*2
            val valuesList = List(listLength){ index ->
                val even = index % 2
                val index2 = ((index - even) / 2)
                Vector2i(index2, even)
            }
            val positionList = startAndEndPointList(streamlines)
            return KDTree(valuesList, positionList)
        }

        /**
         * Provides a list of all points of a point cloud that lie within a mesh.
         * More precise than a test for insideness with bounding boxes.
         *
         * @param mesh of which insideness is tested
         * @param pointCloud list of points from which should be selected
         * @return list of indices of selected points
         * */
        //TODO: Do we need to test watertightness?
        fun insidePoints(mesh : Mesh, pointCloud: List<RealPoint>, transform: Matrix4f): ArrayList<Boolean>{
            val imgJMesh = MeshConverter.toImageJ(mesh)

            // transform mesh vertices according to given transformation matrix
            /*imgJMesh.vertices().forEach{vertex ->
                val vertexPos = vertex.index()
                val currentVertex = Vector4f(vertex.xf(), vertex.yf(), vertex.zf(), 1f)
                transform.transform(currentVertex)
                imgJMesh.vertices().setPositionf(vertexPos, currentVertex.x, currentVertex.y, currentVertex.z)
            }*/

            //TODO: apply Transformation to Mesh
            //val interiorPointTest = InteriorPointTest(imgJMesh, 1.0) //deprecated
            val interiorPointTest = Interior(imgJMesh, 1.0)
            val insideMask = ArrayList<Boolean>(pointCloud.size/2)

            pointCloud.forEachIndexed{index, point ->
                if(index%2==0) insideMask.add(false)
                if (interiorPointTest.isInside(point)) {
                    insideMask[index/2] = true
                }
            }
            return insideMask
        }

        /**
         * Determines list of all start and end points of provided streamlines
         *
         * @param streamlines Streamlines of which start and end points are to be determined
         * @return List of start and end points of all streamlines
         * */
        fun startAndEndPointList(streamlines : java.util.ArrayList<java.util.ArrayList<Vector3f>>): List<RealPoint>{
            val positionsList = List(streamlines.size*2){ index ->
                val even = index % 2
                val index2 = ((index - even) / 2)
                val streamline = streamlines[index2]
                val streamlinepoint = if(even==0) streamline.first() else streamline.last()
                val position = RealPoint(streamlinepoint.x, streamlinepoint.y, streamlinepoint.z)
                position
            }
            return positionsList
        }
    }
}