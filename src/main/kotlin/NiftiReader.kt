import graphics.scenery.*
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.numerics.Random
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import io.scif.img.ImgOpener
import io.scif.img.SCIFIOImgPlus
import net.imglib2.img.Img
import net.imglib2.mesh.Meshes
import net.imglib2.roi.labeling.ImgLabeling
import net.imglib2.roi.labeling.LabelRegions
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.*
import java.nio.file.Paths
import kotlin.math.sqrt

class NiftiReader { //TODO: make dataclass
    companion object{
        fun niftiFromFile(niftiPath: String, hub: Hub, csvPath: String = ""): Node{ //TODO: does it make sense to handle both in the same function, using a default null-String for the csv-path?
            //TODO: version without the need to provide hub, if it's loading a parcellation and not a volume
            //TODO: Check, if this is a possible differentiation: is inttype always parcellation? How to find out, whether it should be read as parcellation or volume later on or do we do this separate from the metadata
            val nifti = ImgOpener().openImgs(niftiPath).get(0)
            val type = nifti.firstElement().javaClass
            //var rawMetadata = HashMap<String, Any>()
            val rawMetadata = getParcellationMetadata(nifti)
            val metadata = transformNiftiMetadata(rawMetadata)
            var node: Node
            if(type == IntType().javaClass){
                node = parcellationFromFile(nifti.img, csvPath) //TODO: perhaps rather do this within the parcellationReader, since this really isn't specific to niftis
            }else if(type == UnsignedShortType().javaClass){
                node = volumeFromFile(niftiPath, hub)
                //TODO: talk about idea, that we first try to load nifti as volume and if this throws exception, we load it in a different style -> like a parcellation??
            }else{
                //TODO: Throw exception, because we do have an unknown type then, Maybe also handle more data formats above
                throw Exception()
            }

            transformNode(node, metadata)

            return node
        }

        fun getParcellationMetadata(nifti: SCIFIOImgPlus<*>) : HashMap<String, Any>{
            var niftiImg = nifti.img
            val niftiMetadataRaw = HashMap<String, Any>()
            nifti.metadata.table.forEach { key, value ->
                niftiMetadataRaw[key] = value
                println(key + " -> " + value)
            }
            return niftiMetadataRaw
        }

        /*fun getVolumeMetadata(path: String, hub: Hub): HashMap<String, Any>{
            val volume = Volume.fromPath(Paths.get(path), hub)
            val m = volume.metadata
            return m
        }*/

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
                //logger.info("Rotation read from nifti is: $quaternion, Axis angle is $axisAngle")

                val pixeldim = floatArrayOf(0.0f, 0.0f, 0.0f)
                pixeldim[0] = map["Voxel width"].toString().toFloat()*100 // TODO: use xyz units parameter (xyz_unity)
                pixeldim[1] = map["Voxel height"].toString().toFloat()*100
                pixeldim[2] = map["Slice thickness"].toString().toFloat()*100
                //logger.info("Pixeldim read from nifti is: ${pixeldim[0]}, ${pixeldim[1]}, ${pixeldim[2]}")

                val offset = Vector3f( //TODO: volume reader divides this by 1000
                    map["Quaternion x parameter"].toString().toFloat() * 1.0f,
                    map["Quaternion y parameter"].toString().toFloat() * -1.0f, //negative due to q-form code of 1
                    map["Quaternion z parameter"].toString().toFloat() * 1.0f,
                )
                //logger.info("QOffset read from nifti is: $offset")

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
                //logger.info("Affine transform read from nifti is: $transform")
                //volume.spatial().wantsComposeModel = false
                tempMap["model"] = transform //Doesn't hold any value
                //TODO: Use this info for alternative transformation
            }
            return tempMap
        }

        fun volumeFromFile(path: String, hub: Hub): Volume{
            return Volume.fromPath(Paths.get(path), hub)
        }

        private fun parcellationFromFile(parcellationImg: Img<out Any>, csvPath: String): Node{
            val parcellationImg = parcellationImg as Img<IntType>

            //Reads in translation between integer labels and label names
            val labelmap = ParcellationReader().readCsv(csvPath)

            //var pixelSet = HashSet<Int>()
            parcellationImg.forEach {pixel ->
                //pixelSet.add(pixel.int)
                if(labelmap.get(pixel.int) == null){
                    pixel.int = 0 //used to make sure, that no "illegal" labels are requested; TODO: We should find out, why there even are labels that can't be found in the label map
                }
            }
            /*pixelSet.forEach {
                Log.info("Label number " + it + " which is brain region " + labelmap.get(it) + " is part of this image")
            }*/
            // variable "labels" is used as a list that maps the pixel values in the image (Indices of the list) to the label number (list value at that index)
            // Since the pxiel values already equal the desired label numbers, this is just a list of subsequent numbers till the highest possible lable number
            val labels = (0 .. (labelmap.keys.maxOrNull()?.toInt() ?: 1)).toList()
            //Log.info("There are " + labels.size + " label numbers")
            val labeling = ImgLabeling.fromImageAndLabels(parcellationImg, labels)
            val regions = LabelRegions(labeling)
            //Log.info("There are " + regions.count() + "regions")

            val parcellationContainer = RichNode()
            val labelMap = ParcellationReader().readCsv(csvPath)
            val brainAreasList = ArrayList<String>()

            regions.forEachIndexed { _, region ->
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
            return parcellationContainer
        }

        fun transformNode(node: Node, metadata: HashMap<String, Any>){
            // Read transformation from parcellation Metadata and apply
            val quaternion = Quaternionf(
                metadata["rotx"].toString().toFloat(),
                metadata["roty"].toString().toFloat(),
                metadata["rotz"].toString().toFloat(),
                metadata["rotw"].toString().toFloat())
            val axisAngle = AxisAngle4f()
            quaternion.get(axisAngle)
            node.spatialOrNull()?.rotation = quaternion //TODO: how to handle when spatial is null?

            if(isVolume(node)){
                node as Volume
                //node.spatialOrNull()?.position = (metadata["translation"] as Vector3f).div(1000f)
                node.spatialOrNull()?.position = (metadata["translation"] as Vector3f).div(1000f)
                node.spatialOrNull()?.scale = (metadata["scale"] as Vector3f)
                node.name = "Volume" //TODO: is this the correct name?
                node.colormap = Colormap.get("grays")
                node.transferFunction = TransferFunction.ramp(0.01f, 0.5f)
            }else{
                node.spatialOrNull()?.position = (metadata["translation"] as Vector3f).div(10.0f)
                node.spatialOrNull()?.scale = (metadata["scale"] as Vector3f).div(100f) //TODO: further up, it is multiplied by 100 -> we can get rid of both
                node.name = "Brain areas"
            }

            //TODO: volume additionally node.spatialOrNUll()?.wantsComposeModel = false -> only in case of S-form Code > 0
            //TODO: volume additionally node.spatialOrNull()?.model = transform -> only in case of S-form Code > 0
            //TODO: volume additionally node.origin = Origin.Center
        }

        fun isVolume(node: Node): Boolean{ //TODO: use this function to actually check, if this can be a volume, currently only valid in this specific case; should be used in the beginning of this class also
            try {
                node as Volume
                return true
            }catch (e: Exception){
                return false
            }
        }

        /*fun Any?.toFloat(): Float { //TODO: Solve StackOverFlow error, when using this Code instead of the .toString .toFloat
            return try {
                this.toString().toFloat()
            } catch (e: NumberFormatException) {
                0f //TODO: What do I want to happen if there is a NumberFormatException? Perhaps I don't want a catch to happen, but rather the exception getting thrown
            }
        }*/
    }
}