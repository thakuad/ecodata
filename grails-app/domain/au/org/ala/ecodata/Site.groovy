package au.org.ala.ecodata

import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.operation.valid.IsValidOp
import com.vividsolutions.jts.operation.valid.TopologyValidationError
import grails.converters.JSON
import org.bson.types.ObjectId
import org.geotools.geojson.geom.GeometryJSON

class Site {

    /*
    Associations:
        sites may belong to 0..n Projects - a list of projectIds are stored in each site
        sites may have 0..n Activities/Assessments - mapped from the Activity side
    */

    static mapping = {
        name index: true
        siteId index: true
        projects index: true
        version false
    }

    ObjectId id
    String siteId
    String status = 'active'
    String visibility
    String externalSiteId
    List projects = []
    String name
    String type
    String description
    String habitat
    String area
    String recordingMethod
    String landTenure
    String protectionMechanism
    String notes
    Date dateCreated
    Date lastUpdated
    Boolean isSciStarter = false
    Map extent
    Map geoIndex

    static constraints = {
        visibility nullable: true
        name nullable: true
        externalSiteId nullable:true
        type nullable:true
        description nullable:true, maxSize: 40000
        habitat nullable:true
        area nullable:true
        recordingMethod nullable:true
        landTenure nullable:true
        protectionMechanism nullable:true
        notes nullable:true, maxSize: 40000
        isSciStarter nullable: true
        extent nullable: true
        geoIndex nullable: true, validator: { value, site ->
            // Checks validity of GeoJSON object
            if(value && value.type != 'Circle'){
                Geometry geom = new GeometryJSON().read((value as JSON).toString())
                IsValidOp isValidOp = new IsValidOp(geom);
                TopologyValidationError error = isValidOp.getValidationError()
                if(error){
                    String errorMsg = error?.toString()
                    log.error ("Site shape is not valid. ${errorMsg}")
                    ['inValidShape', errorMsg]
                }
            }
        }
    }

    def getAssociations(){
      def map = [:]
      projects.each { map.put("Project", it)}
    }

    /**
     * Remove duplicate co-ordinates that appear consecutively. Such co-ordinates causes an exception during indexing.
     */
    def beforeValidate(){
        if(geoIndex && geoIndex.type == 'Polygon'){
            List coordinates = geoIndex.coordinates
            List vettedPolygons = []

            coordinates.each { List polygon ->
                List vettedCoordinates = []
                List previousPoint

                polygon?.each { List point ->
                    if(!point.equals(previousPoint)){coordinates
                        vettedCoordinates.add(point)
                    } else if(previousPoint == null){
                        vettedCoordinates.add(point)
                    } else {
                        log.debug("Duplicate points identified in site id ${siteId} - ${point}")
                    }

                    previousPoint = point
                }

                vettedPolygons.add(vettedCoordinates)
            }

            if(vettedPolygons){
                geoIndex.coordinates = vettedPolygons
                extent?.geometry?.coordinates = vettedPolygons
            }
        }
    }
}
