import com.opencsv.CSVReader
import graphics.scenery.Blending
import graphics.scenery.Node
import graphics.scenery.RichNode
import graphics.scenery.numerics.Random
import net.imglib2.img.Img
import net.imglib2.mesh.Meshes
import net.imglib2.roi.labeling.ImgLabeling
import net.imglib2.roi.labeling.LabelRegions
import net.imglib2.type.numeric.integer.IntType
import org.joml.*
import org.slf4j.LoggerFactory
import java.io.FileReader


class ParcellationReader {
    companion object{
        private val logger = LoggerFactory.getLogger(TractogramTools::class.java)
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
                        val name = record!![1]
                        val color = Vector4d(record!![2].toDouble(), record!![3].toDouble(), record!![4].toDouble(), record!![5].toDouble())
                        dict[id] = Pair(name, color)
                        colorList.add(color)
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
         * Merges parcellation image with information of the .csv label map to get a LabelRegions object from which
         * meshes are generated. These meshes are then added to a scenery node.
         *
         * @param parcellationImg Parcellation image that was read from a file
         * @param csvPath Path to .csv label map
         * @return Scenery node containing all meshes of the parcellation
         * */
        fun parcellationFromImg(parcellationImg: Img<out Any>, csvPath: String): Node {
            val img = parcellationImg as Img<IntType> // TODO: check cast

            //Reads in translation between integer labels and label names
            val labelMap = readCsv(csvPath)

            val faultyPixelSet = HashSet<Int>()
            img.forEach {pixel ->
                if(labelMap[pixel.int] == null){
                    faultyPixelSet.add(pixel.int)
                    pixel.int = 0 //used to make sure, that no "illegal" labels are requested; TODO: We should find out, why there even are labels that can't be found in the label map, perhaps warn-message
                }
            }
            if(faultyPixelSet.size>0){
                logger.warn("The following pixel values of the parcellation are not part of the label map and were set to 0: $faultyPixelSet")
            }

            // variable "labels" is used as a list that maps the pixel values in the image (Indices of the list) to the label number (list value at that index)
            // Since the pixel values already equal the desired label numbers, this is just a list of subsequent numbers till the highest possible label number
            val labels = (0 .. (labelMap.keys.maxOrNull()?.toInt() ?: 1)).toList()
            val labeling = ImgLabeling.fromImageAndLabels(img, labels)
            val regions = LabelRegions(labeling)

            val parcellationContainer = RichNode()
            val brainAreasList = ArrayList<String>()

            regions.forEachIndexed { _, region ->
                // Generate mesh with imagej-ops
                val m = Meshes.marchingCubes(region)

                // Convert mesh into a scenery mesh for visualization
                val mesh = MeshConverter.toScenery(m, false, flipWindingOrder = true)
                mesh.name = labelMap[region.label]?.first ?: "undefined region"
                mesh.material().diffuse = Random.random3DVectorFromRange(0.2f, 0.8f)
                brainAreasList.add(mesh.name)

                // Scale, since all tractogram related files are scaled to fit into the window
                mesh.spatial().scale = Vector3f(0.1f, 0.1f, 0.1f)
                // Add mesh to relevant container to be displayed in the scene
                parcellationContainer.addChild(mesh)
                // Add material to be visualized
                mesh.materialOrNull().blending =
                    Blending(transparent = true, opacity = 0.5f, sourceColorBlendFactor = Blending.BlendFactor.SrcAlpha,
                        destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha)
            }
            brainAreasList.add(0, "None")
            parcellationContainer.metadata["brainAreas"] = brainAreasList
            return parcellationContainer
        }
    }
}

