import graphics.scenery.*
import graphics.scenery.geometry.Curve //Use with scenery commit 7a924aba (older code) to display Streamlines
import graphics.scenery.geometry.UniformBSpline
//import graphics.scenery.geometry.curve.BaseShapesFromSingleShape //Use with scenery curve_restructuring-SNAPSHOT (newer code) to display Streamlines
//import graphics.scenery.geometry.curve.DefaultCurve //Use with scenery curve_restructuring-SNAPSHOT (newer code) to display Streamlines
import graphics.scenery.trx.TRXReader
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.joml.*
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.collections.ArrayList
import kotlin.math.*


/**
 * Visualizing streamlines with a basic data set.
 *
 * @author Justin Buerger <burger@mpi-cbg.de>
 * @author Ulrik Guenther <hello@ulrik.is>
 */
class TractogramTools(maximumStreamlineCount: Int = 1000, private val scene: Scene, private val hub: Hub){

    enum class ColorMode {
        LocalDirection,
        GlobalDirection
    }

    private var colorMode = ColorMode.GlobalDirection
    private var verticesOfStreamlines = ArrayList<ArrayList<Vector3f>>()
    private var _maximumStreamlineCount: Int = maximumStreamlineCount
    private val logger = LoggerFactory.getLogger(TractogramTools::class.java)

    /**
     * Class to hold the relevant components for tractogram analysis and visualization: tractogram, parcellation and
     * container objects to hold them. The tractogram is the scene object that contains the streamlines and the
     * parcellation is the scene object that contains the meshes of the single brain regions.
     * Structure of the scene:
     * - container
     *  - tractogramParent
     *      - tractogram
     *      - parcellationObject
     *  - volume
     * */
    data class Components(val container: RichNode, val tractogramParent: RichNode, val tractogram: RichNode,
                          val parcellationObject: RichNode)
    /**
     * Set up component structure by loading relevant files and creating scene objects and containers for them.
     *
     * @param trx Path to the .trx file of the tractogram
     * @param parcellationPath Path to the .nifti file of the parcellation
     * @param csvPath Path to the .csv file of the label map
     * @param volumeDataset Path to the .nifti file of the volume
     * @return Components object that contains the scene objects of the tractogram, the parcellation and the container
     * */
    fun setUp(trx: String, parcellationPath: String, csvPath: String, volumeDataset: String): Components{
        val container = RichNode()
        container.spatial().rotation = Quaternionf().rotationX(-PI.toFloat()/2.0f)
        container.name = "brain parent"

        //load tractogram from file
        val tractogram = tractogramGameObject(trx)
        val tractogramParent = RichNode()
        tractogramParent.addChild(tractogram)
        tractogramParent.name = "tractogram parent"
        container.addChild(tractogramParent)

        //load parcellation from file
        val parcellationObject = NiftiReader.parcellationFromFile(parcellationPath, csvPath = csvPath) as RichNode
        tractogramParent.addChild(parcellationObject)

        // Load nifti volume from file
        val volume = NiftiReader.niftiFromFile(volumeDataset, hub)
        applyInvTractogramTransform(tractogram, volume) //TODO: tractogram, volume and parcellation need to align
        container.addChild(volume)

        return Components(container, tractogramParent, tractogram, parcellationObject)
    }

    fun getMaxStreamlines(): Int{
        return _maximumStreamlineCount
    }

    /**
     * Class to hold information about a streamline number change.
     *
     * @param reduction Boolean that indicates, if the streamline number was reduced (true) or increased (false)
     * @param streamlines Scene object of the streamlines to either add (if reduction is false) or remove
     * (if reduction is true)
     * */
    data class StreamlineNumberData(val reduction : Boolean, val streamlines : RichNode)
    /**
     * If the maximal number of streamlines to be displayed is changed, this function creates information about
     * which streamlines to add or remove from the scene.
     * In case of a streamline reduction (maximumStreamlineCount gets lowered), it stores the streamlines to be removed
     * together with "true" for reduction in an object of type StreamlineNumberData.
     * In case of a streamline increase (maximumStreamlineCount gets increased), it stores all streamline objects (the
     * ones that already exist and the ones that are newly created) together with "false" for reduction in an object of
     * type StreamlineNumberData.
     *
     * @param streamlinesToShow List of all streamlines that could be displayed, randomized arrangement, so that the
     * first n streamlines can be taken without any further randomization
     * @param oldTractogram Scene object of the tractogram that contains the streamlines that are currently displayed
     * @param numStreamlines Number of streamlines that should be displayed
     * @return StreamlineNumberData object that contains information about which streamlines to add or remove from the
     * scene
     * */
    fun changeNumberOfStreamlines(streamlinesToShow: ArrayList<ArrayList<Vector3f>>, oldTractogram: RichNode, numStreamlines: Int) : StreamlineNumberData{
        _maximumStreamlineCount = numStreamlines

        var reduction = true
        var streamlineList = RichNode()
        val numStreamlinesOld = oldTractogram.children.size

        if(numStreamlines>numStreamlinesOld){
            var numbering = numStreamlinesOld
            val filteredList = streamlinesToShow.filterIndexed { index, _ -> (index>=numStreamlinesOld) && index<numStreamlines}
            displayableStreamlinesFromVerticesList(filteredList).children.forEach {
                it.name = "Streamline #$numbering"
                oldTractogram.addChild(it)
                numbering++
            }
            streamlineList = oldTractogram
            reduction = false
        }else if(numStreamlines<numStreamlinesOld){
            val filteredList = oldTractogram.children.filterIndexed { index, _ -> (index>=numStreamlines)}
            filteredList.forEach{
                streamlineList.addChild(it)
            }
        }

        return StreamlineNumberData(reduction, streamlineList)
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
        val transformTractogram = tractogram.metadata["voxelToRasMatrix"] as Matrix4f
        val transformCopy = Matrix4f()
        transformCopy.set(transformTractogram)
        transformTractogram.invert()
        if (transformCopy.mul(transformTractogram).equals(Matrix4f().identity(), maxAcceptedDifference)) {
            logger.info("Inversion matrix and original matrix are inverse to each other with an acceptable error.")
        } else {
            logger.warn(
                "The inversion matrix and the original matrix are not inverse to each other with an acceptable error " +
                        "of max $maxAcceptedDifference per cell. The original transformation matrix needs to be " +
                        "applied to the tractogram instead of taking this route."
            )
        }
        // TODO: Apply transformation directly to the tractogram (especially if warning gets displayed)

        /*
        Decomposing the matrix to get components that can directly be applied to the volume scene object. The following
        code can be deleted once there is a way to apply the transformation matrix as a whole to the volume.
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
        // TODO: Combine information from both sources (volume transformation and inverse tractogram transformation)
        //  with sensible scaling factors (10, 100, 1000 maybe) in order to align tractogram (+parcellation)
        //  with the volume

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
    }

    /**
     * Function performs preparation steps, scene adaptations (changing visibilities), a streamline selection,
     * checks on that selection and finally creates a scene object with the selected streamlines.
     *
     * @param tractogram Scene object of the tractogram, which contains streamlines previously displayed
     * @param parcellationObject Scene object of the parcellation, which contains the meshes of the brain regions
     * @param meshes List of meshes that should be used for streamline selection
     * @param inclusion Boolean that indicates, if the streamlines that are selected should be included (true) or
     * excluded (false)
     * @return Scene object of the selected streamlines
     * */
    fun streamlineSelectionTransformedMesh(parcellationObject: Node, tractogram: RichNode, meshes: List<Mesh>,
                                           inclusion: Boolean = true): RichNode {
        scene.spatial().updateWorld(true)
        encodeTransformInMesh(tractogram, parcellationObject)
        parcellationObject.children.forEach { child ->
            child.visible = false
        }
        var selectedStreamlines = verticesOfStreamlines
        meshes.forEach{ mesh ->
            mesh.visible = true
            selectedStreamlines = StreamlineSelector.preciseStreamlineSelection(mesh, selectedStreamlines, inclusion)
                    as java.util.ArrayList<java.util.ArrayList<Vector3f>>
        }

        tractogram.visible = false
        var selectedTractogram = RichNode()
        try {
            val shuffledSelectedStreamlines = selectedStreamlines.shuffled()
            selectedTractogram = displayableStreamlinesFromVerticesList(
                shuffledSelectedStreamlines
                    .take(_maximumStreamlineCount) as ArrayList<ArrayList<Vector3f>>
            )
            selectedTractogram.name = "Tractogram Selection"
            selectedTractogram.metadata["Streamlines"] = shuffledSelectedStreamlines
            //tractogram.parent?.addChild(selectedTractogram)
        }catch (e: Exception){
            if(selectedStreamlines.isEmpty()){
                logger.warn("Empty list of streamlines. No streamline selection will be displayed.")
            }else{
                logger.warn("Exception occurred: ${e.message}. No streamline selection will be displayed.")
            }
        }
        return selectedTractogram
    }

    /**
     * Encodes local transforms of parcellation meshes and the parcellation object (parent of all meshes) into
     * the vertices of the meshes; Then transforms the meshes and the parcellation object back, so that their
     * positions, orientations and scaling haven't changed visually.
     *
     * @param tractogram Scene object of the tractogram
     * @param parcellationObject Scene object of the parcellation, which contains the meshes of the brain regions
     * */
    private fun encodeTransformInMesh(
        tractogram: RichNode,
        parcellationObject: Node
    ) {
        scene.spatial().updateWorld(true)

        //  Check assumptions, that single Streamlines and tractogram as a whole are not transformed locally
        //  TODO: if this assumption is not met, the local transforms need to be applied to the vertices of the
        //   streamlines, before selecting streamlines
        tractogram.children.forEach { streamline ->
            val spatial = streamline.spatialOrNull()
            val localTransform = spatial?.model ?: Matrix4f().identity()
            if(localTransform != Matrix4f().identity()){
                logger.warn("Streamline ${streamline.name} is transformed by local matrix: $localTransform." +
                        "Streamline selection might not work correctly, since it assumes no transform of the single" +
                        "streamline.")
            }
        }
        val tractogramLocalTransform = tractogram.spatial().model
        if(tractogramLocalTransform != Matrix4f().identity()){
            logger.warn("Tractogram is transformed by local matrix: ${tractogramLocalTransform}." +
                    "Streamline selection might not work correctly, since it assumes no transform of the tractogram.")
        }

        // Get local parcellation transformation matrix
        val localParcellationTransform = parcellationObject.spatialOrNull()?.model ?: run {
            logger.warn("Model transform of test mesh is null. Applying identity matrix to the vertices instead.")
            Matrix4f().identity()
        }

        /*
             * Check assumption, that applying local transform of parcellation to the world transformation of its parent
             * will be equivalent to the world transformation of the parcellation itself. The local transform of the
             * parcellation is a concatenation of translation, rotation and scaling. If the calculated transforms don't
             * match up, there might be an additional shearing component.
        */
        val worldTransformParcellationParent = Matrix4f().set(parcellationObject.parent?.spatialOrNull()?.world
            ?: Matrix4f().identity())
        worldTransformParcellationParent.mul(localParcellationTransform)
        if(!worldTransformParcellationParent.equals(parcellationObject.spatialOrNull()?.world, 0.0001f)){
            logger.warn("World transform of parcellation object does not match with composition of world transform" +
                    "of it's parent with local transform of itself. There might be a shearing component that" +
                    "is not accounted for in the model matrix (local transform).")
        }

        parcellationObject.children.forEach {mesh ->
            mesh as Mesh
            val localMeshTransform = mesh.spatial().model
            val verticesBuffer = mesh.geometry().vertices

            verticesBuffer.rewind()
            while (verticesBuffer.remaining() >= 3) {
                //multiply each vertex with local transform
                val currentPos = verticesBuffer.position()
                val currentVertex = Vector4f(verticesBuffer.get(), verticesBuffer.get(), verticesBuffer.get(), 1f)
                localMeshTransform.transform(currentVertex)
                localParcellationTransform.transform(currentVertex)
                verticesBuffer.put(currentPos, currentVertex.x)
                verticesBuffer.put(currentPos + 1, currentVertex.y)
                verticesBuffer.put(currentPos + 2, currentVertex.z)
                verticesBuffer.position(currentPos + 3)
            }

            mesh.spatial().position = Vector3f(0f, 0f, 0f)
            mesh.spatial().scale = Vector3f(1f, 1f, 1f)
            mesh.spatial().rotation = Quaternionf(0f, 0f, 0f, 1f)
            mesh.geometry().dirty = true
            verticesBuffer.rewind()

            mesh.spatial().updateWorld(true)
        }

        parcellationObject.spatialOrNull()?.position = Vector3f(0f, 0f, 0f)
        parcellationObject.spatialOrNull()?.scale = Vector3f(1f, 1f, 1f)
        parcellationObject.spatialOrNull()?.rotation = Quaternionf(0f, 0f, 0f, 1f)
        scene.spatial().updateWorld(true)
    }

    /**
     * Try to add a volume as a tiff that was previously created via Fiji. Currently not working, since the tiff
     * doesn't get displayed.
     * TODO: Fix this function to display a tiff volume with correct and automatically loaded transformation
     *
     * @param container Scene object that should contain the volume
     * @param volumeTiffPath Path to the .tiff file of the volume
     * */
    private fun addTiffVolume(container: RichNode, volumeTiffPath: String){
        val volumeTiff = Volume.fromPath(Paths.get(volumeTiffPath), hub)
        volumeTiff.colormap = Colormap.get("grays")
        val tf = TransferFunction()
        tf.addControlPoint(0.0f, 0.0f) // for pixel value 0, alpha is 0 (completely transparent)
        tf.addControlPoint(0.01f/65535.0f, 0.0f) // for pixel value 100, alpha is 0 (completely transparent)
        tf.addControlPoint(1f / 65535.0f, 1.0f)
        tf.addControlPoint(1017.0f / 65535.0f, 1.0f) // for pixel value 1017, alpha is 1 (completely opaque)
        tf.addControlPoint(5001.0f / 65535.0f, 1.0f) // for pixel value 5001, alpha is 1 (completely opaque)
        tf.addControlPoint(3017.0f / 65535.0f, 1.0f)
        volumeTiff.transferFunction = tf

        // Hard coded information from nifti volume, since the metadata did not change (checked while debugging)
        // TODO: read metadata from tiff volume directly
        volumeTiff.spatial().scale = Vector3f(
            0.43169886f * 100f,
            0.4339234f * 100f,
            0.6199905f * 100
        ) //normally volume is loaded with *100 scaling, tractogram would be loaded without any factor applied to the scaling
        volumeTiff.spatial().position =
            Vector3f(107.386f, -63.4674f, 119.598f).div(1000f) //for tractogram only divided by 10
        val x = -0.0049300706f
        val y = -0.9989844f
        val z = 0.04474579f
        val w = sqrt(1.0 - (x * x + y * y + z * z)).toFloat()
        volumeTiff.spatial().rotation = Quaternionf(x, y, z, w)
        container.addChild(volumeTiff)
    }

    /**
     * Loads tractogram from file and creates a scene object that contains a random selection of the streamlines
     * stored in the file.
     *
     * @param trx Path to the .trx file of the tractogram
     * @return Scene object of the tractogram containing the streamline representations
     * */
    private fun tractogramGameObject(trx: String): RichNode {
        val streamlinesAndTransform = tractogramFromFile(trx)
        verticesOfStreamlines = streamlinesAndTransform.streamlines.shuffled() as ArrayList<ArrayList<Vector3f>>
        val tractogram = displayableStreamlinesFromVerticesList(verticesOfStreamlines.take(_maximumStreamlineCount)
                as ArrayList<ArrayList<Vector3f>>)
        tractogram.metadata["voxelToRasMatrix"] = streamlinesAndTransform.voxelToRasMatrix
        tractogram.metadata["Streamlines"] = verticesOfStreamlines
        return tractogram
    }

    data class TractogramData(val streamlines: ArrayList<ArrayList<Vector3f>>, val voxelToRasMatrix: Matrix4f)
    /**
     * Reads a .trx file given by a path and returns a streamline list and a transformation matrix.
     *
     * @param path Path to the .trx file of the tractogram
     * @return TractogramData object that contains the streamline list and the transformation matrix
     * */
    private fun tractogramFromFile(path: String): TractogramData{
        val trx = TRXReader.readTRX(path)
        val transform = Matrix4f(trx.header.voxelToRasMM)
        transform.transpose()

        logger.info("Transform of tractogram is: ${transform}.")

        val tempStreamlineList = ArrayList<ArrayList<Vector3f>>()
        trx.streamlines.forEachIndexed { _, line ->
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
     * Calculates a curve representation for each streamline and determines a geometry that can be rendered.
     * In the process, metrics like fiber length and curvature of the streamlines are calculated and stored
     * into metadata.
     *
     * @param listVertices List of streamlines (which are lists of vertices / vector3f-points)
     * @return List of streamline scene-objects that can be rendered
     * */
    private fun displayableStreamlinesFromVerticesList(listVertices: List<java.util.ArrayList<Vector3f>>) : RichNode {
        var timeStamp0 = 0.toLong()
        var timeStampSplineSize = 0.toLong()
        var timeStampGeo = 0.toLong()
        logger.info("Display of ${listVertices.size} streamlines")
        var maxLen = 0f
        var maxCurve = 0f
        var maxAvCurve = 0f
        val tractogram = RichNode()
        List<Node>(listVertices.size){index ->
            val vecVerticesNotCentered = listVertices[index]
            val color = vecVerticesNotCentered.fold(Vector3f(0.0f)) { lhs, rhs -> (rhs - lhs).normalize() }
            val catmullRom = UniformBSpline(vecVerticesNotCentered, 2)
            timeStamp0 = System.nanoTime()
            val splineSize = catmullRom.splinePoints().size
            timeStampSplineSize = System.nanoTime()
            val geo = Curve(catmullRom, partitionAlongControlpoints = false) { triangle(splineSize) } //Uses Code from scenery commit 7a924aba (older code)
            // Following code uses code of scenery branch curve-restructuring (newer code)
            /*val tri = listOf(
            Vector3f(0.1f, 0.1f, 0f).times(0.1f),
            Vector3f(0.1f, -0.1f, 0f).times(0.1f),
            Vector3f(-0.1f, -0.1f, 0f).times(0.1f),
            )
            val geo = DefaultCurve(catmullRom,
                { BaseShapesFromSingleShape.shapes(tri, catmullRom.splinePoints().size) })*/
            //TODO: SingleMeshCurve instead of DefaultCurve, if Streamlines are uni-color, because it's faster
            timeStampGeo = System.nanoTime()
            geo.name = "Streamline #$index"
            var lengthGeo = 0f
            var sumCurvature = 0f

            // Color for all curve segments will be the same if SingleMeshCurve is used
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
            vecVerticesNotCentered.forEachIndexed{ i, _ ->
                val localLen : Float = if (i<(streamlineSize-1)) vecVerticesNotCentered[i+1].distance(vecVerticesNotCentered[i]) else 0f
                lengthGeo += localLen

                // The following code is a comment since this can only be used with the curve-restructuring branch of scenery
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
            geo.metadata["length"] = lengthGeo
            maxLen = max(maxLen, lengthGeo)
            geo.metadata["average curvature"] = sumCurvature/(streamlineSize-2)
            geo.metadata["maximum curvature"] = maxLocalCurve
            geo.metadata["minimum curvature"] = minLocalCurve
            maxAvCurve = max(maxAvCurve, sumCurvature/(streamlineSize-2))
            maxCurve = max(maxCurve, maxLocalCurve)
            tractogram.addChild(geo)
            geo
        }

        logger.info("Maximum curvature is $maxCurve, maximum average curve is $maxAvCurve Maximum Fiber length is $maxLen")
        logger.info("Time for splineSize: ${(timeStampSplineSize-timeStamp0)*listVertices.size/1000000}, " +
                "Time for creating curve-geometry: ${(timeStampGeo-timeStampSplineSize)*listVertices.size/1000000}")
        tractogram.name = "Tractogram"
        tractogram.metadata["maxLength"] = maxLen

        return tractogram
    }

    companion object {
        private val baseList = listOf(
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