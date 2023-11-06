import com.esotericsoftware.minlog.Log
import com.opencsv.CSVReader
import graphics.scenery.SceneryBase
import graphics.scenery.utils.extensions.times
import io.scif.img.ImgOpener
import net.imglib2.img.Img
import net.imglib2.roi.labeling.ImgLabeling
import net.imglib2.roi.labeling.LabelRegions
import net.imglib2.type.numeric.integer.IntType
import org.joml.*
import java.io.FileReader
import java.io.InputStream
import kotlin.math.sqrt

class ParcellationReader {
    val idList : MutableList<Int> = mutableListOf()
    var parcellationMetadata = HashMap<String, Any>()

    /**
     * Reads .csv file that contains a mapping between label numbers and their names and colors.
     * Writes this mapping into a HashMap
     *
     * @param csvFile Path to csv file that is read
     * @return HashMap with mapping between label number (key) and label name and color (value)
     * */
    fun readCsv(csvFile: String): HashMap<Int, Pair<String, Vector4d>> {
        val dict : HashMap<Int, Pair<String, Vector4d>> = hashMapOf()
        val colorList : MutableList<Vector4d> = mutableListOf()
        //TODO: check correct file format

        try {
            val fileReader = FileReader(csvFile)
            val csvReader = CSVReader(fileReader)

            var record: Array<String>?
            while (csvReader.readNext().also { record = it } != null) {
                val id = record?.get(0)?.toIntOrNull() ?: continue
                if(record!!.size == 6){
                    val name = record!!.get(1)
                    val color = Vector4d(record!!.get(2).toDouble(), record!!.get(3).toDouble(), record!!.get(4).toDouble(), record!!.get(5).toDouble())
                    dict.put(id, Pair(name, color))
                    idList.add(id)
                    colorList.add(color)
                    //println(dict.get(id)!!.first) //prints the names of all brain regions within the parcellation file
                }
            }

            csvReader.close()
            fileReader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return dict
    }

    /**
     * Reads parcellation .nifti and merges it with information of the .csv label map to get a LabelRegions object.
     * Reads out metadata of the .nifti file and attaches it to the ParcellationReader object.
     *
     * @param parcellationPath Path to parcellation .nifti
     * @param csvPath Path to .csv label map
     * @return LabelRegions
     * */
    fun loadParcellationAsLabelRegions(parcellationPath: String, csvPath: String): LabelRegions<Int> {
        /*
        * Reads the nifti-parcellation file with SCIFIO, gets the ImgPlus from it, extracts the ImgLib2 Img:
        * Used to get a RandomAccessible representation of the parcellation
        * This representation is casted to one that holds integer-values and these integer-values can be used to define regions
        * These regions can get labels assigned (which have the same number as the region) and translated back into label names
        * Also, they are converted to meshes which are needed for the further operations
        * */
        //TODO: Check correct file format
        //TODO: automatically get parcellation and label mapping from parent folder
        //TODO: Check if reading and converting the parcellation like this is deprecated or stably works like this
        //TODO: Support other file formats additionally to .csv
        //read parcellation, Conversion to Img (ImgLib2 RandomAccessible)
        val parcellation = ImgOpener().openImgs(parcellationPath).get(0)
        var parcellationImg = parcellation.img
        val parcellationMetadataRaw = HashMap<String, Any>()
        parcellation.metadata.table.forEach { key, value ->
            parcellationMetadataRaw[key] = value
            println(key + " -> " + value)
        }
        parcellationMetadata = transformNiftiMetadata(parcellationMetadataRaw)

        if(parcellation.firstElement().javaClass == IntType().javaClass){
            parcellationImg = parcellationImg as Img<IntType>

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
            return regions
        }else{
            Log.error("the parcellation file does not have the required Integer data type!")
            return LabelRegions(null) //TODO: Improve Error handling: is an Exception perhaps better suited?
        }
    }

    /**
     * Used to translate .nifti metadata to HashMap that is easier to read and interprete.
     * Currently only used for transformation information but can be extended to also translate more metadata.
     *
     * @param map original map of .nifti metadata that follows .nifti conventions
     * @return map of metadata that is more easily interpretable
     * */
    //TODO: Reading .niftis should be a function outside of this class, since it's both needed in here, but also for reading volume .niftis in other classes
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

            val offset = Vector3f(
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
        }
        return tempMap
    }
}

