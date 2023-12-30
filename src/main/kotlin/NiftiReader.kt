import graphics.scenery.*
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import io.scif.img.ImgOpener
import io.scif.img.SCIFIOImgPlus
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.*
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.math.sqrt

class NiftiReader {
    companion object{
        private val logger = LoggerFactory.getLogger(TractogramTools::class.java)
        /**
         * Reads nifti volume from file and returns it as a scenery node.
         *
         * @param niftiPath Path to nifti file
         * @param hub Hub to be used for reading the volume
         * @return Node containing the volume
         * */
        fun niftiFromFile(niftiPath: String, hub: Hub): Node{
            val nifti = ImgOpener().openImgs(niftiPath)[0]
            val type = nifti.firstElement().javaClass
            val rawMetadata = getMetadata(nifti)
            val metadata = transformNiftiMetadata(rawMetadata)
            val node: Node
            if(type == UnsignedShortType().javaClass){
                node = Volume.fromPath(Paths.get(niftiPath), hub)
            }else{
                logger.error("Nifti $niftiPath does not contain a volume of pixel type UnsignedShort, but instead of $type " +
                        "and is thus not supported by this reader.")
                node = Volume()
            }
            transformNode(node, metadata)
            return node
        }

        /**
         * Reads nifti parcellation from file and returns it as a scenery node.
         *
         * @param niftiPath Path to nifti file
         * @param csvPath Path to csv file containing the label map
         * @return Node containing the parcellation
         * */
        fun niftiFromFile(niftiPath: String, csvPath: String): Node{
            val nifti = ImgOpener().openImgs(niftiPath)[0]
            val type = nifti.firstElement().javaClass
            val rawMetadata = getMetadata(nifti)
            val metadata = transformNiftiMetadata(rawMetadata)
            val node: Node
            if(type == IntType().javaClass){
                node = ParcellationReader.parcellationFromImg(nifti.img, csvPath) //TODO: rather do this within the parcellationReader, since this really isn't specific to niftis
            }else{
                val logger = LoggerFactory.getLogger(TractogramTools::class.java)
                logger.error("Nifti $niftiPath does not contain a parcellation of pixel type Int, but instead of $type " +
                        "and is thus not supported by this reader.")
                node = RichNode()
            }

            transformNode(node, metadata)
            return node
        }

        // TODO: Add additional niftiFromFile function, that generally loads a nifti, even if it's not a volume or parcellation

        /**
         * Reads metadata from nifti scifico-ImgPlus object and returns it as a HashMap. This works with all nifti files.
         * */
        private fun getMetadata(nifti: SCIFIOImgPlus<*>) : HashMap<String, Any>{
            val niftiMetadataRaw = HashMap<String, Any>()
            nifti.metadata.table.forEach { key, value ->
                niftiMetadataRaw[key] = value
            }
            return niftiMetadataRaw
        }

        /**
         * Used to translate .nifti metadata to HashMap that is easier to read and interpret.
         * Currently only used for transformation information but can be extended to also translate more metadata.
         *
         * @param map original map of .nifti metadata that follows .nifti conventions
         * @return map of metadata that is more easily interpretable
         * */
        fun transformNiftiMetadata(map: HashMap<String, Any>): HashMap<String, Any>{
            val transform = Matrix4f()
            val tempMap = HashMap<String, Any>()
            if(map["Q-form Code"].toString().toFloat() > 0) { //method 2 of NIfTI for reading
                val x = map["Quaternion b parameter"].toString().toFloat()
                val y = map["Quaternion c parameter"].toString().toFloat()
                val z = map["Quaternion d parameter"].toString().toFloat()
                val w = sqrt(1.0-(x*x + y*y + z*z)).toFloat()
                val quaternion = Quaternionf(x, y, z, w)
                val axisAngle = AxisAngle4f()
                quaternion.get(axisAngle)
                logger.info("Rotation read from nifti is: $quaternion, Axis angle is $axisAngle")

                val pixeldim = floatArrayOf(0.0f, 0.0f, 0.0f)
                pixeldim[0] = map["Voxel width"].toString().toFloat() // TODO: use xyz units parameter (xyz_unity)
                pixeldim[1] = map["Voxel height"].toString().toFloat()
                pixeldim[2] = map["Slice thickness"].toString().toFloat()
                logger.info("Pixeldim read from nifti is: ${pixeldim[0]}, ${pixeldim[1]}, ${pixeldim[2]}")

                val offset = Vector3f( //TODO: volume reader divides this by 1000
                    map["Quaternion x parameter"].toString().toFloat() * 1.0f,
                    map["Quaternion y parameter"].toString().toFloat() * -1.0f, //negative due to q-form code of 1
                    map["Quaternion z parameter"].toString().toFloat() * 1.0f,
                )
                logger.info("QOffset read from nifti is: $offset")

                // transformations that were given by the read metadata
                tempMap["rotx"] = x
                tempMap["roty"] = y
                tempMap["rotz"] = z
                tempMap["rotw"] = w
                tempMap["scale"] = Vector3f(pixeldim)
                tempMap["translation"] = offset

            } else if (map["S-form Code"].toString().toFloat()>0) { // method 3 of NIfTI for reading
                for(i in 0..2){
                    for(j in 0..3){
                        val coordinate: String = when(i){
                            0 -> "X"
                            1 -> "Y"
                            2 -> "Z"
                            else -> throw IllegalArgumentException()
                        }
                        val value = map["Affine transform $coordinate[$j]"]?.toString()?.toFloat() ?: throw NullPointerException()
                        transform.setRowColumn(i, j, value)
                    }
                }
                transform.setRow(3, Vector4f(0F, 0F, 0F, 1F))
                //val matrix4ftransp = matrix4f.transpose() //transposing should not happen to this matrix, since translation is the last column -> column major
                logger.info("Affine transform read from nifti is: $transform")
                //volume.spatial().wantsComposeModel = false
                tempMap["model"] = transform //Doesn't hold any value
                //TODO: Use this info for alternative transformation
            }
            return tempMap
        }

        /**
         * Transforms node according to given metadata
         *
         * @param node Node to be transformed
         * @param metadata Metadata that contains transformation information
         * */
        private fun transformNode(node: Node, metadata: HashMap<String, Any>){
            // Read transformation from parcellation Metadata and apply
            val quaternion = Quaternionf(
                metadata["rotx"].toString().toFloat(),
                metadata["roty"].toString().toFloat(),
                metadata["rotz"].toString().toFloat(),
                metadata["rotw"].toString().toFloat())
            val axisAngle = AxisAngle4f()
            quaternion.get(axisAngle)
            node.spatialOrNull()?.rotation = quaternion

            if(isVolume(node)){
                node as Volume
                node.spatialOrNull()?.position = (metadata["translation"] as Vector3f).div(1000f)
                node.spatialOrNull()?.scale = (metadata["scale"] as Vector3f).mul(100f)
                node.name = "Volume"
                node.colormap = Colormap.get("grays")
                node.transferFunction = TransferFunction.ramp(0.01f, 0.5f)
            }else{
                node.spatialOrNull()?.position = (metadata["translation"] as Vector3f).div(10.0f)
                node.spatialOrNull()?.scale = (metadata["scale"] as Vector3f)
                node.name = "Brain areas"
            }

            //TODO: check if S-form Code was greater than 0 and if so:
            // node.spatialOrNUll()?.wantsComposeModel = false
            // node.spatialOrNull()?.model = transform
            //TODO: try setting node.origin = Origin.Center
        }

        /**
         * Check if node can be cast to a volume.
         *
         * @param node Node to be checked
         * @return true if node can be cast to a volume, false otherwise
         * */
        private fun isVolume(node: Node): Boolean{
            try {
                node as Volume
                return true
            }catch (e: Exception){
                return false
            }
        }
    }
}