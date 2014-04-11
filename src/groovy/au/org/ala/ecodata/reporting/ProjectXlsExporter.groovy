package au.org.ala.ecodata.reporting
import au.org.ala.ecodata.MetadataService
import au.org.ala.ecodata.metadata.OutputModelProcessor
import pl.touk.excel.export.getters.Getter
import pl.touk.excel.export.multisheet.AdditionalSheet
/**
 * Created by god08d on 11/04/14.
 */
class ProjectXlsExporter {

    def projectHeaders = ['Project ID', 'Grant ID', 'External ID', 'Organisation', 'Name', 'Description', 'Program', 'Sub-program']

    def projectProperties = ['projectId', 'grantId', 'externalId', 'organisationName', 'name', 'description', 'associatedProgram', 'associatedSubProgram']

    def siteHeaders = ['Site ID', 'Name', 'Description', 'lat', 'lon']
    def siteProperties = ['siteID', 'name', 'description', 'lat', 'lon']
    def activityHeaders = ['Project ID','Activity ID', 'Planned Start date', 'Planned End date', 'Description', 'Activity Type', 'Theme', 'Status']
    def activityProperties = ['projectId', 'activityId', 'plannedStartDate', 'plannedEndDate', 'description', 'type', 'mainTheme', 'progress']

    def metadataService
    def XlsExporter exporter

    AdditionalSheet projectSheet
    AdditionalSheet sitesSheet
    AdditionalSheet activitiesSheet

    Map<String, List<AdditionalSheet>> outputSheets = [:]

    public ProjectXlsExporter(XlsExporter exporter, MetadataService metadataService) {
        this.exporter = exporter
        this.metadataService = metadataService
    }

    public void export(project) {

        projectSheet()
        sitesSheet()
        activitiesSheet()

        int row = projectSheet.getSheet().lastRowNum
        projectSheet.add([project], projectProperties, row+1)

        if (project.sites) {
            def sites = project.sites.collect {
                [siteId:it.siteId, name:it.name, description:it.description, lat:it.extent?.geometry?.centre[1], lon:it.extent?.geometry?.centre[0]]
            }
            row = sitesSheet.getSheet().lastRowNum
            sitesSheet.add(sites, siteProperties, row+1)
        }
        if (project.activites) {

            def outputsByType = [:].withDefault { [] }

            row = sitesSheet.getSheet().lastRowNum
            activitiesSheet.add(project.activites, activityProperties, row+1)

            project.activites.each { activity ->
                activity?.outputs?.each { output ->
                    outputsByType[output.name] << output
                }
            }


            outputsByType.each { outputName, data ->
                def config = outputProperties(outputName)
                if (config.headers) {
                    def expandedHeaders = ['Project ID', 'Activity ID', *config.headers]
                    if (!outputSheets[outputName]) {
                        outputSheets[outputName] = exporter.addSheet(outputName, expandedHeaders)
                    }
                    AdditionalSheet outputSheet = outputSheets[outputName]
                    row = outputSheet.sheet.lastRowNum


                    def getters = [new ConstantGetter('projectId', project.projectId), 'activityId', *config.propertyGetters]
                    outputSheet.add(data, getters, row+1)
                }
            }
        }
    }

    public void exportAll(List projects) {
        projects.each { export(it) }
    }

    AdditionalSheet projectSheet() {
        if (!projectSheet) {
            projectSheet = exporter.addSheet('Project', projectHeaders)
        }
        projectSheet
    }

    AdditionalSheet sitesSheet() {
        if (!sitesSheet) {
            sitesSheet = exporter.addSheet('Sites', siteHeaders)
        }
        sitesSheet
    }

    AdditionalSheet activitiesSheet() {
        if (!activitiesSheet) {
            activitiesSheet = exporter.addSheet('Activities', activityHeaders)
        }
        activitiesSheet
    }


    def outputProperties(name) {
        def model = metadataService.annotatedOutputDataModel(name)

        def headers = []
        def properties = []
        model.each {
            if (it.dataType == 'list') {
                it.columns.each { col ->
                    properties << it.name+'.'+col.name
                    headers << col.label
                }
            }
            else if (it.dataType in ['photoPoints', 'matrix']) {
                // not supported, do nothing.
            }
            else {
                properties << it.name
                headers << it.description
            }
        }
        def propertyGetters = properties.collect{new OutputDataPropertiesBuilder(it, model)}
        [headers:headers, propertyGetters:propertyGetters]
    }

    class ConstantGetter implements Getter<String> {

        def name, value

        public ConstantGetter(name, value) {
            this.name = name
            this.value = value
        }
        @Override
        String getPropertyName() {
            return name
        }

        @Override
        String getFormattedValue(Object object) {
            return value
        }
    }

    class Value implements OutputModelProcessor.ProcessingContext {
        public Value(value) {
            this.value = value
        }
        def value
    }


    class OutputDataPropertiesBuilder extends OutputModelProcessor implements OutputModelProcessor.Processor<Value>, Getter<String> {

        private String[] nameParts
        private List outputDataModel

        public OutputDataPropertiesBuilder(String name, outputDataModel) {
            this.nameParts = name.tokenize('.'[0]);
            this.outputDataModel = outputDataModel;
        }


        // Implementation of OutputModelProcessor.Processor
        @Override
        def number(Object node, Value outputValue) {
            def val = outputValue.value
            return val?val as String:""
        }

        @Override
        def integer(Object node, Value outputValue) {
            def val = outputValue.value
            return val?val as String:""
        }

        @Override
        def text(Object node, Value outputValue) {
            def val = outputValue.value
            return val?val as String:""
        }

        @Override
        def date(Object node, Value outputValue) {
            return new Value(outputValue?:"") // dates are UTC formatted strings already
        }

        @Override
        def image(Object node, Value outputValue) {
            return ""
        }

        @Override
        def embeddedImages(Object node, Value outputValue) {
            return ""
        }

        @Override
        def species(Object node, Value outputValue) {
            def val = outputValue.value

            return val?val.name:""
        }

        @Override
        def stringList(Object node, Value outputValue) {
            def val = outputValue.value
            if (val instanceof List) {
                val = val.join(',')
            }
            return val?:""
        }

        // Implementation of Getter<String>
        @Override
        String getPropertyName() {
            return nameParts.join('.');
        }

        @Override
        String getFormattedValue(Object output) {

            def node = outputDataModel
            for (String part : nameParts) {
                def tmpNode = node.find{it.name == part}
                // List typed model elements have a cols element containing nested nodes.
                node = tmpNode.columns?:tmpNode
            }

            processNode(this, node, getValue(output))

        }

        def getValue(outputModelOrData) {
            def value = outputModelOrData.data
            for (String part : nameParts) {
                value = value[part]
            }
            new Value(value)
        }
    }

}
