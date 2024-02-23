import graphics.scenery.*
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.utils.forEachIndexedAsync
import graphics.scenery.utils.forEachParallel
import kotlinx.coroutines.*
import net.imglib2.KDTree
import net.imglib2.RealPoint
import net.imglib2.algorithm.kdtree.ClipConvexPolytopeKDTree
import net.imglib2.algorithm.kdtree.ConvexPolytope
import net.imglib2.algorithm.kdtree.HyperPlane
import net.imglib2.mesh.alg.Interior
import org.joml.Vector2i
import org.joml.Vector3f
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.concurrent.thread
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Provides functionality to select streamlines of a given List, by using meshes as the selection criteria.
 * Only streamlines that start or end within the given mesh are selected. A single Streamline is given by a list
 * of points.
 *
 * It needs to be assured, that both the mesh and streamline points are in the same coordinate system and space,
 * before using the functions.
 * */
class StreamlineSelector: SceneryBase("No arms, no cookies", windowWidth = 1280, windowHeight = 720)  {
    companion object {
        val PointDispatcher = newFixedThreadPoolContext(8, "PointsWorker")

        /**
         * Determines all streamlines that precisely start or end in a given mesh.
         * Make sure that the mesh and streamlines are in the same space, before using this method!
         *
         * @param sceneryMesh Scenery-Mesh that is used to select streamlines
         * @param streamlines List of all streamlines to be selected from
         * @return List of streamlines that got selected
         * */
        fun preciseStreamlineSelection(sceneryMesh: Mesh, streamlines : java.util.ArrayList<java.util.ArrayList<Vector3f>>, inclusion: Boolean = true) : List<java.util.ArrayList<Vector3f>>{
            val imgJMesh = MeshConverter.toImageJ(sceneryMesh)

            val insideMask = insidePoints(imgJMesh, startAndEndPointList(streamlines))
            val streamlineSelection = streamlines.filterIndexed { index, _ ->
//                insideMask.getOrNull(index) == inclusion
                insideMask.contains(index) == inclusion
            }
            return streamlineSelection
        }

        /**
         * Determines Streamlines that start or end within the Bounding box of a given 3-polytope. This function
         * only works correctly, if all transformations of the polytope are encoded in its coordinates.
         *
         * @param selectedArea Node that holds the 3-Polytope which defines the area in which Streamlines have to start of finish to be selected
         * @param streamlines All streamlines from which should be selected
         * @return List of selected Streamlines (List of List of Points in a single streamline) that start or finish in the given polytope
         * */
        // TODO: might not work on all datasets, check Hyperplane creation for correctness if translation is different from (0,0,0)
        fun streamlineSelectionFromPolytope(selectedArea: HasSpatial?, streamlines: java.util.ArrayList<java.util.ArrayList<Vector3f>>): java.util.ArrayList<java.util.ArrayList<Vector3f>> {
            /*
            * Calculation of the Hyperplanes that form the bounding box.
            * The Hyperplanes form the polytope which is input to the following algorithm.
            * */
            // calculations of the hyperplane distances (second argument):
            // normal vector *(point product) point
            // here: translation+extend of the bounding box/normal vector length (here: 1 or -1)
            val cubePos = selectedArea?.spatial()?.position ?: throw NullPointerException()
            val boundingBox = selectedArea.boundingBox ?: throw NullPointerException()
            val hyperplane1 = HyperPlane(0.0,0.0,-1.0, boundingBox.max.z.toDouble().plus(cubePos.z).times(-1))
            val hyperplane2 = HyperPlane(-1.0,0.0,0.0, boundingBox.max.x.toDouble().plus(cubePos.x).times(-1))
            val hyperplane3 = HyperPlane(0.0,0.0,1.0, boundingBox.min.z.toDouble().plus(cubePos.z))
            val hyperplane4 = HyperPlane(1.0,0.0,0.0, boundingBox.min.x.toDouble().plus(cubePos.x))
            val hyperplane5 = HyperPlane(0.0, -1.0,0.0, boundingBox.max.y.toDouble().plus(cubePos.y).times(-1))
            val hyperplane6 = HyperPlane(0.0, 1.0,0.0, boundingBox.min.y.toDouble().plus(cubePos.y))
            val polytope = ConvexPolytope(hyperplane1, hyperplane2, hyperplane3, hyperplane4, hyperplane5, hyperplane6)


            /*
            * Create KD-tree as the data structure holding all points where streamlines start or end.
            * A new KD-tree only gets created, if the initial amount of streamlines already got reduced,
            * since an initial KD-tree gets created in init().
            * */
            val localKDTree =  createKDTree(streamlines)
            //TODO: Think of a new way to not always recreate the KD-tree:
            // the global variables globalKDTree and verticesOfStreamlines come from the Streamlines-Class
            /*if(streamlines == verticesOfStreamlines){
                localKDTree = globalKDTree
            }else{
                localKDTree = createKDTree(streamlines)
            }*/


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
            return streamlineSelection
        }

        /**
         * Creates an imglib2 KDTree data structure that only contains all starting and end points of a given streamline list.
         *
         * @param streamlines List of list of vertices contained in a single streamline.
         * Only the first and last vertex of every streamline is stored inside the KD-tree, because these are the points
         * which need to efficiently be searched.
         * @return KD-tree data structure holding all points where a streamline either starts or ends
         * */
        private fun createKDTree(streamlines: java.util.ArrayList<java.util.ArrayList<Vector3f>>) : KDTree<Vector2i> {
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
         * @param imgJMesh of which insideness is tested
         * @param pointCloud list of points from which should be selected
         * @return list of indices of selected points
         * */
        //TODO: Do we need to test watertightness?
//        fun insidePoints(imgJMesh : net.imglib2.mesh.Mesh, pointCloud: List<RealPoint>): Array<Boolean>{
        fun insidePoints(imgJMesh : net.imglib2.mesh.Mesh, pointCloud: List<RealPoint>): Set<Int>{
            val m = measureTimedValue {
                val threadLocalTest = ThreadLocal.withInitial { Interior(imgJMesh, 1.0) }
                val insideMask = Collections.synchronizedSet(HashSet<Int>(pointCloud.size / 2))

                val index = AtomicInteger(0)
                val f = { i: Int, point: RealPoint ->
                    if(threadLocalTest.get().isInside(point)) {
                        insideMask.add(i / 2)
                    }
                }
                runBlocking(PointDispatcher) { pointCloud.map { async { f(index.getAndIncrement(), it) } }.joinAll() }

                insideMask
            }

            println("Took ${m.duration.inWholeMilliseconds} ms")
            return m.value
        }

        /**
         * Determines list of all start and end points of provided streamlines
         *
         * @param streamlines Streamlines of which start and end points are to be determined
         * @return List of start and end points of all streamlines
         * */
        private fun startAndEndPointList(streamlines : java.util.ArrayList<java.util.ArrayList<Vector3f>>): List<RealPoint>{
            val positionsList = List(streamlines.size*2){ index ->
                val even = index % 2
                val index2 = ((index - even) / 2)
                val streamline = streamlines[index2]
                val streamlinePoint = if(even==0) streamline.first() else streamline.last()
                val position = RealPoint(streamlinePoint.x, streamlinePoint.y, streamlinePoint.z)
                position
            }
            return positionsList
        }
    }
}