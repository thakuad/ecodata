package au.org.ala.ecodata

import org.grails.datastore.mapping.query.api.BuildableCriteria

/**
 * Processes requests related to activity forms.
 */
class ActivityFormService {

    private String INVALID_INDEX_KEY = 'activityForm.invalidIndex'

    /**
     * Returns the activity form identified by name and formVersion.  If formVersion is not supplied, the
     * activity form with the highest version that is also published will be returned.
     * @param name the name of the form.
     * @param formVersion (optional) the version of the form.
     * @return
     */
    ActivityForm findActivityForm(String name, Integer formVersion = null) {

        ActivityForm form
        if (formVersion != null) {
            form = ActivityForm.findByNameAndFormVersionAndStatusNotEqual(name, formVersion, Status.DELETED)
        }
        else {
            List forms = ActivityForm.findAllByNameAndPublicationStatusAndStatusNotEqual(name, PublicationStatus.PUBLISHED, Status.DELETED)
            form = forms.max{it.formVersion}
        }
        form
    }

    ActivityForm[] findVersionedActivityForm(String name) {
        ActivityForm[] forms = ActivityForm.findAllByNameAndPublicationStatusAndStatusNotEqual(name, PublicationStatus.PUBLISHED, Status.DELETED)
        forms
    }

    ActivityForm[] list(){
       return ActivityForm.findAllWhereStatusNotEqualAndPublicationStatusEquals(Status.DELETED, PublicationStatus.PUBLISHED)
    }

    ActivityForm findByTemplateName(String templateName){
        List forms = ActivityForm.where {
            status != Status.DELETED
            publicationStatus == PublicationStatus.PUBLISHED
            sections { templateName == templateName}
        }.list()

        ActivityForm form = forms.max{it.version}
    }

    /**
     * Creates a model equivalent to the legacy activities-model.json for client API compatibility.
    */
    public Map activitiesModel() {
        Map activitiesModel = [activities:[], outputs:[]]

        // We are expecting the number of versions per form to be small for the life
        // of this deprecated API  (in most cases 1) so are querying all forms and collecting only the
        // maximum version rather than creating a more complex query (which isn't
        // well supported by the current version of the mongo gorm plugin).
        Map maxVersionsByName = [:]
        Map activitiesByName = [:]

        list().each { ActivityForm activityForm ->
            Map activityModel = [
                    name: activityForm.name,
                    gmsId: activityForm.gmsId,
                    category: activityForm.category,
                    supportsSites: activityForm.supportsSites,
                    supportsPhotoPoints: activityForm.supportsPhotoPoints,
                    type: activityForm.type,
                    minOptionalSectionsCompleted: activityForm.minOptionalSectionsCompleted,
                    outputConfig: [],
                    outputs: [],
                    fromVersion: activityForm.formVersion
            ]

            activityForm.sections.each { FormSection section ->
                activityModel.outputConfig << [
                        outputName: section.name,
                        optional: section.optional,
                        collapsedByDefault: section.collapsedByDefault,
                        optionalQuestionText: section.optionalQuestionText
                ]

                activityModel.outputs << [
                        name: section.name,
                        template: section.templateName,
                        title: section.title,
                        scores: [] // Unused
                ]
            }

            activitiesByName[activityFormId] = activityModel
        }
    }

    /**
     * Returns a List of Maps of the form [name:<activity name>, formVersions:[<array of available versions>]
     * Used for activity form selection in the administration interface.
     */
    List<Map> activityVersionsByName() {
        List activities = ActivityForm.where {
            status != Status.DELETED
            projections {
                property('name')
                property('formVersion')
            }
            order('name', 'asc')
            order('formVersion', 'desc')
        }.list()

        Map grouped = activities.collect{[name:it[0], formVersion:it[1]]}.groupBy{it.name}
        activities = grouped.collect{k, v -> [name:k, formVersions:v.collect{it.formVersion}]}
        // The criteria query sort doesn't seem to be working, but the list isn't that long anyway.
        activities.sort{it.name}
        activities.each{ it.formVersions.sort{-it} }
        activities
    }


//    ActivityForm findByActivityTypeAndVersion(String activityType, String formVersion){
//        BuildableCriteria criteria = ActivityForm.createCriteria()
//
//        ActivityForm af =   criteria.list {
//            sections {
//                eq('name',outputName)
//            }
//            ne("status",Status.DELETED)
//            eq("publicationStatus", PublicationStatus.PUBLISHED)
//        }.first()
//        return af
//    }



    /**
     * Publishes an activity form.  This makes it available for selection by the "latest published version"
     * mechanism (findActivityForm with a null formVersion)
     * @param name the name of the form.
     * @param formVersion the version of the form.
     * @return
     */
    ActivityForm publish(String activityFormName, Integer version) {
        ActivityForm form = ActivityForm.findByNameAndFormVersion(activityFormName, version)
        if (form) {
            form.publish()
            form.save()
        }
        form
    }

    /**
     * Un-publishes an activity form (returns the publicationStatus to DRAFT).
     * This makes it unavailable for selection by the "latest published version"
     * mechanism (findActivityForm with a null formVersion)
     * @param name the name of the form.
     * @param formVersion the version of the form.
     * @return
     */
    ActivityForm unpublish(String activityFormName, Integer version) {
        ActivityForm form = ActivityForm.findByNameAndFormVersion(activityFormName, version)
        if (form) {
            form.unpublish()
            form.save()
        }
        form
    }

    ActivityForm newDraft(String activityFormName) {
        ActivityForm form = ActivityForm.findAllByNameAndStatusNotEqual(activityFormName, Status.DELETED).max{it.formVersion}
        if (form) {

            if (form.isPublished()) {
                ActivityForm newForm = new ActivityForm(
                        name: form.name,
                        type: form.type,
                        supportsSites: form.supportsSites,
                        supportsPhotoPoints: form.supportsPhotoPoints,
                        category: form.category,
                        formVersion: form.formVersion + 1,
                        gmsId: form.gmsId,
                        minOptionalSectionsCompleted: form.minOptionalSectionsCompleted,
                        sections: form.sections
                )
                newForm.save()
                form = newForm
            } else {
                form.errors.reject("activityForm.latestVersionIsInDraft")
            }
        }
        form
    }

    /**
     * Validates and saves an ActivityForm.
     */
    ActivityForm save(ActivityForm activityForm) {
        activityForm.sections.each { FormSection section ->
            Map validationResult = metadataService.isDataModelValid(section.template)
            if (!validationResult.valid) {
                activityForm.errors.reject(INVALID_INDEX_KEY, [section.name, validationResult.errorInIndex.join(',')] as Object[], "Invalid indicies in template ${section.name}")
            }
        }
        if (!activityForm.hasErrors()) {
            activityForm.save()
        }
        activityForm
    }

}
