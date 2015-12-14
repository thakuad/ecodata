package au.org.ala.ecodata

import au.org.ala.ecodata.reporting.CSProjectXlsExporter
import au.org.ala.ecodata.reporting.ProjectExporter
import au.org.ala.ecodata.reporting.ProjectXlsExporter
import au.org.ala.ecodata.reporting.ShapefileBuilder
import au.org.ala.ecodata.reporting.SummaryXlsExporter
import au.org.ala.ecodata.reporting.XlsExporter
import grails.converters.JSON
import groovyx.net.http.ContentType
import groovy.json.JsonSlurper
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsParameterMap
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.search.SearchHit

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static au.org.ala.ecodata.ElasticIndex.*
import java.text.SimpleDateFormat

class SearchController {

    static final String PUBLISHED_ACTIVITIES_FILTER = 'publicationStatus:published'

    SearchService searchService
    ElasticSearchService elasticSearchService
    ReportService reportService
    ProjectService projectService
    MetadataService metadataService
    DocumentService documentService
    ActivityService activityService
    SiteService siteService

    def index(String query) {
        def list = searchService.findForQuery(query, params)
        render list as JSON
    }

    def elastic() {
        def res = elasticSearchService.search(params.query, params, DEFAULT_INDEX)
        response.setContentType("application/json; charset=\"UTF-8\"")
        render res
    }

    def elasticHome() {
        Map geoSearch = null
        if (params.geoSearchJSON) {
            geoSearch = new JsonSlurper().parseText(params.geoSearchJSON)
        }
        def res = elasticSearchService.search(params.query, params, HOMEPAGE_INDEX, geoSearch)
        response.setContentType("application/json; charset=\"UTF-8\"")
        render res
    }

    /*
    * Searches the given query in project activity context.
    * Requires API key to prevent unauthorized access to embargoed records.
    */
    @RequireApiKey
    def elasticProjectActivity(){
        elasticSearchService.buildProjectActivityQuery(params)
        def res = elasticSearchService.search(params.query, params, PROJECT_ACTIVITY_INDEX)
        response.setContentType("application/json; charset=\"UTF-8\"")
        render res
    }

    private def populateGeoInfo(markBy, hit, selectedFacetTerms){

        def geo = hit.source.geo
        if(!markBy) {
            geo[0].geometry = hit.source.sites[0].extent.geometry
            return geo
        }

        def legendName, index
        def name =  hit.source[markBy.replaceAll("Facet", "")] ?: hit.source[markBy.replaceAll("Facet", "Name")] ?:""

        if(name){
            for(int i = 0; i < selectedFacetTerms.size(); i++){
                if(selectedFacetTerms[i].legendName.equals(name)){
                    legendName = selectedFacetTerms[i].legendName
                    index = selectedFacetTerms[i].index
                    selectedFacetTerms[i].count++
                    break;
                }
            }

            geo.each{ data ->
                data.legendName = legendName
                data.index = index
            }
        }
        else {
            hit.source.sites.each { site ->
                if(site.extent?.geometry) {
                    name =  site.extent?.geometry[markBy.replaceAll("Facet", "")] ?:
                            site.extent?.geometry[markBy.replaceAll("Facet", "Name")] ?: ""

                    if(name) {
                        for(int i = 0; i < selectedFacetTerms.size(); i++){
                            if(selectedFacetTerms[i].legendName.equals(name)){
                                legendName = selectedFacetTerms[i].legendName
                                index = selectedFacetTerms[i].index
                                selectedFacetTerms[i].count++
                                break;
                            }
                        }

                        geo.each{ data ->
                            if(data.siteId.equals(site.siteId)) {
                                data.legendName = legendName
                                data.index = index
                            }
                        }
                    }
                }
            }
        }

        geo
    }

    def elasticGeo() {
        def res = elasticSearchService.search(params.query, params, "homepage")
        def selectedFacetTerms = []
        def markBy = params.markBy

        if(markBy){
            res.facets.facets.each{ facet ->
                if(facet.key.equals(markBy)){
                    facet.value.eachWithIndex{ val, index ->
                        def data = [:]
                        data.legendName = val.term.toString()
                        data.index = index
                        data.count = 0
                        selectedFacetTerms << data
                    }
                }
            }
        }

        def geoRes = []

        res.hits.hits.each { hit ->
            if(hit.source?.geo) {
                def proj = [:]
                proj.projectId = hit.source.projectId
                proj.name = hit.source.name
                proj.org = hit.source.organisationName
                proj.geo = populateGeoInfo(markBy, hit, selectedFacetTerms)

                geoRes << proj
            }
        }
        response.setContentType("application/json; charset=\"UTF-8\"")
        def projectsAndTotal = ['total':res.hits.getTotalHits(),'projects':geoRes,'selectedFacetTerms':selectedFacetTerms]

        render projectsAndTotal as JSON
    }
    def elasticPost() {
        def paramsObj = request.JSON
        def paramMap = new GrailsParameterMap(paramsObj, request)
        log.debug "paramMap = ${paramMap}"

        if (paramMap) {
            def res = elasticSearchService.search(paramMap.query, paramMap, "")
            response.setContentType("application/json; charset=\"UTF-8\"")
            render res
        } else {
            def msg = [error: "Required JSON body not found"]
            render msg as JSON
        }
    }

    def clearIndex() {
        log.debug "Clearing index"
        render elasticSearchService.deleteIndex()
    }

    def indexAll() {
        render elasticSearchService.indexAll() as JSON
    }

    def dashboardReport() {

        def filters = params.getList("fq")
        def additionalFilters = [PUBLISHED_ACTIVITIES_FILTER]
        additionalFilters.addAll(filters)
        def results = reportService.aggregate(additionalFilters)
        render results as JSON
    }

    def scoresByLabel() {
        def scores = params.getList("scores")

        def filters = params.getList("fq")
        def searchTerm = params.query
        def additionalFilters = [PUBLISHED_ACTIVITIES_FILTER]
        additionalFilters.addAll(filters)
        def results = reportService.aggregate(additionalFilters, searchTerm, reportService.findScoresByLabel(scores))
        render results as JSON
    }

    def targetsReportByScoreLabel() {
        def scoreLabels = params.getList("scores")
        def scores = reportService.findScoresByLabel(scoreLabels)
        def filters = params.getList("fq")
        def searchTerm = params.query
        def additionalFilters = [PUBLISHED_ACTIVITIES_FILTER]

        additionalFilters.addAll(filters)
        def targets = reportService.outputTargetsBySubProgram(params, scores)
        def scoresReport = reportService.outputTargetReport(additionalFilters, searchTerm, scores)

        def results = [scores:scoresReport, targets:targets]
        render results as JSON
    }

    def targetsReport() {
        def filters = params.getList("fq")
        def additionalFilters = [PUBLISHED_ACTIVITIES_FILTER]
        additionalFilters.addAll(filters)
        def targets = reportService.outputTargetsBySubProgram(params)
        def scores = reportService.outputTargetReport(additionalFilters)

        def results = [scores:scores, targets:targets]
        render results as JSON
    }

    def report() {

        def filters = params.getList("fq")

        def results = reportService.runReport(filters, 'Green Army Monthly Summary', params)
        render results as JSON
    }

    @RequireApiKey
    def downloadAllData() {
        if (params.containsKey("isMerit") && !params.isMerit.toBoolean()) {
            downloadProjectData(params)
        } else {
            downloadMeritData(params)
        }
    }

    private downloadMeritData(GrailsParameterMap params) {
        defaultDownloadQueryParams(params)

        Set ids = getProjectIdsForDownload(params, HOMEPAGE_INDEX)

        withFormat {
            json {
                List projects = ids.collect { projectService.get(it, ProjectService.ALL) }
                render projects as JSON
            }
            xlsx {
                XlsExporter exporter = exportProjectsToXls(ids, true)
                exporter.setResponseHeaders(response)

                exporter.save(response.outputStream)
            }
        }
    }

    private static defaultDownloadQueryParams(params) {
        if (!params.max) {
            params.max = 5000
            params.offset = 0
        }
    }

    /**
     * Constructs a zip file with the following structure:
     * |- data.xls --> spreadsheet as per {@link CSProjectXlsExporter}
     * |- images
     * |--- <projectId> --> one directory for each projectId
     * |--- |- <documentId> --> one file for each project-level image
     * |--- |--- <activityId> --> one directory for each activityId
     * |--- |--- |- <documentId> --> one file for each activity-level image
     * |--- |--- |- <outputId> --> one directory for each outputId
     * |--- |--- |--- |- <documentId> --> one file for each output-level image
     * |- shapes
     * |--- <projectId> --> one directory for each projectId
     * |--- |- extent.zip --> shapefile for the project extent
     * |--- |- sites.zip  --> shapefile containing all sites for the project
     *
     * @param params
     * @return
     */
    private downloadProjectData(GrailsParameterMap params) {
        elasticSearchService.buildProjectActivityQuery(params)

        response.setContentType(ContentType.BINARY.toString())
        response.setHeader('Content-Disposition', 'Attachment;Filename="data.zip"')

        defaultDownloadQueryParams(params)

        Set<String> projectIds = getProjectIdsForDownload(params, PROJECT_ACTIVITY_INDEX)

        XlsExporter xlsExporter = exportProjectsToXls(projectIds, false, "data")

        new ZipOutputStream(response.outputStream).withStream { zip ->
            zip.putNextEntry(new ZipEntry("data.xls"))
            ByteArrayOutputStream xslFile = new ByteArrayOutputStream()
            xlsExporter.save(xslFile)
            xslFile.flush()
            zip << xslFile.toByteArray()
            xslFile.flush()
            xslFile.close()

            addShapeFilesToZip(zip, projectIds);

            addImagesToZip(zip, projectIds)

            zip.finish()
        }
    }

    private addShapeFilesToZip(ZipOutputStream zip, Set<String> projectIds) {
        zip.putNextEntry(new ZipEntry("shapefiles/"))

        projectIds.each { projectId ->
            Map project = projectService.get(projectId, ProjectService.ALL)
            if (project.sites) {
                project.sites.each { site ->
                    zip.putNextEntry(new ZipEntry("shapefiles/${site.siteId}.zip"))
                    ShapefileBuilder builder = new ShapefileBuilder(projectService, siteService)
                    builder.setName(site.siteId)
                    builder.addSite(site.siteId)
                    builder.writeShapefile(zip)
                }
            }
        }
    }

    private addImagesToZip(ZipOutputStream zip, Set<String> projectIds) {
        zip.putNextEntry(new ZipEntry("images/"))

        projectIds.each { projectId ->
            zip.putNextEntry(new ZipEntry("images/${projectId}/"))

            groupDocumentsByActivityAndOutput(projectId).each { activityId, documentsMap ->
                if (activityId) {
                    zip.putNextEntry(new ZipEntry("images/${projectId}/${activityId}/"))

                    documentsMap.each { outputId, documentList ->
                        if (outputId) {
                            zip.putNextEntry(new ZipEntry("images/${projectId}/${activityId}/${outputId}/"))

                            documentList.each { Map doc ->
                                if (doc.type == Document.DOCUMENT_TYPE_IMAGE) {
                                    addFileToZip(zip, "images/${projectId}/${activityId}/${outputId}/", doc)
                                }
                            }
                        } else {
                            documentList.each { Map doc ->
                                if (doc.type == Document.DOCUMENT_TYPE_IMAGE) {
                                    addFileToZip(zip, "images/${projectId}/${activityId}/", doc)
                                }
                            }
                        }
                    }
                } else {
                    documentsMap[null].each { Map doc ->
                        if (doc.type == Document.DOCUMENT_TYPE_IMAGE) {
                            addFileToZip(zip, "images/${projectId}/", doc)
                        }
                    }
                }
            }
        }
    }

    private addFileToZip(ZipOutputStream zip, String zipPath, Map doc) {
        zip.putNextEntry(new ZipEntry("${zipPath}/${doc.filename}"))

        String path = "${grailsApplication.config.app.file.upload.path}${File.separator}${doc.filepath}${File.separator}${doc.filename}"

        File file = new File(path)

        if (file.exists()) {
            file.withInputStream { i -> zip << i }
        } else {
            log.error("Document exists with file ${doc.filepath}/${doc.filename}, but the corresponding file at ${path} does not exist!")
        }
    }

    private Map<String, Map<String, List<Map>>> groupDocumentsByActivityAndOutput(String projectId) {
        Map<String, Map<String, List<Map>>> documents = [:].withDefault { [:].withDefault { [] } }

        activityService.findAllForProjectId(projectId).each { activity ->
            documentService.findAllForActivityId(activity.activityId)?.each {
                documents[it.activityId ?: null][it.outputId ?: null] << it
            }
        }

        documents
    }

    private XlsExporter exportProjectsToXls(Set<String> projectIds, boolean merit, String fileName = "results") {
        long start = System.currentTimeMillis()

        XlsExporter xlsExporter = new XlsExporter(fileName)

        ProjectExporter projectExporter
        if (merit) {
            projectExporter = new ProjectXlsExporter(xlsExporter)
        } else {
            projectExporter = new CSProjectXlsExporter(xlsExporter)
        }

        Project.withSession { session ->
            int batchSize = 50
            List projects = new ArrayList(batchSize)
            for (int i = 0; i < projectIds.size(); i++) {
                projects << projectService.get(projectIds[i], ProjectService.ALL)

                if (i % batchSize == batchSize - 1 || i == projectIds.size() - 1) {
                    projectExporter.exportAll(projects)
                    projects.clear()
                    session.clear()

                    log.info "Exported ${i + 1} of ${projectIds.size()} projects..."
                }
            }
        }
        log.info "Export of ${projectIds.size()} projects took ${System.currentTimeMillis() - start} millis"

        xlsExporter
    }

    private Set<String> getProjectIdsForDownload(Map params, String searchIndexName) {
        long start = System.currentTimeMillis()

        SearchResponse res = elasticSearchService.search(params.query, params, searchIndexName)
        Set ids = new HashSet()

        for (SearchHit hit : res.hits.hits) {
            if (hit.source.projectId) {
                ids << hit.source.projectId
            }
        }

        log.info "Query of ${ids.size()} projects took ${System.currentTimeMillis() - start} millis"

        ids
    }

    @RequireApiKey
    def downloadSummaryData() {

        def defaultCategory = "Not categorized"
        def filters = params.getList("fq")
        def additionalFilters = [PUBLISHED_ACTIVITIES_FILTER] + filters

        def results = reportService.aggregate(additionalFilters)
        def scores = results.outputData
        def scoresByCategory = scores.groupBy{
            (it.score.category?:defaultCategory)
        }

        withFormat {
            json {
                render scoresByCategory as JSON
            }
            xlsx {
                XlsExporter exporter = new XlsExporter("results")
                exporter.setResponseHeaders(response)

                SummaryXlsExporter summaryXlsExporter = new SummaryXlsExporter(exporter)
                summaryXlsExporter.exportAll(scoresByCategory)
                exporter.sizeColumns()

                exporter.save(response.outputStream)
            }
        }
    }

    /** Temporary method to assist generating the user report.  Needs work */
    def userReport() {

        def users = reportService.userSummary()

        File out = new File('/Users/god08d/Documents/MERIT/users/userReport.csv')
        out.withWriter { writer ->
            writer.println("User Id, Name, Email, Role, Project ID, Grant ID, External ID, Project Name, Project Access Role")

            users.values().each { user->

                writer.print(user.userId+","+user.name+","+user.email+","+user.role+",")
                if (user.projects) {
                    boolean first = true
                    user.projects.each { project ->
                        if (!first) {
                            writer.print(",,,,")
                        }
                        writer.println(project.projectId+","+project.grantId+","+project.externalId+","+project.name+","+project.access)
                        first = false
                    }
                }


            }


        }
    }

    @RequireApiKey
    def downloadShapefile() {

        if (!params.max) {
            params.max = 1000
            params.offset = 0
        }
        def query = params.query
        if (!query) {
            query = '*'
        }

        SearchResponse res = elasticSearchService.search(query, params, "homepage")

        Set ids = new HashSet()

        for (SearchHit hit : res.hits.hits) {
            if (hit.source.projectId) {
                ids << hit.source.projectId
            }
        }

        if (ids.size() > 0) {

            SimpleDateFormat format = new SimpleDateFormat('yyyy-MM-dd')
            def name = 'meritSites-' + format.format(new Date())
            response.setContentType("application/zip")
            response.setHeader("Content-disposition", "filename=${name}.zip")
            reportService.exportShapeFile(ids, name, response.outputStream)
            response.outputStream.flush()
        }
        else {
            response.setStatus(400)
            render "No project sites selected for download"
        }
    }

}
