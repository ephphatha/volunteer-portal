package au.org.ala.volunteer

import au.org.ala.cas.util.AuthenticationCookieUtils
import com.google.common.base.Stopwatch
import com.google.common.base.Strings
import grails.converters.JSON
import grails.web.servlet.mvc.GrailsParameterMap
import org.apache.commons.io.FileUtils
import org.jooq.DSLContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest

import static au.org.ala.volunteer.jooq.tables.TaskDescriptor.TASK_DESCRIPTOR
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static javax.servlet.http.HttpServletResponse.*

class ProjectController {

    static allowedMethods = [save: "POST", update: "POST", delete: "POST",
                             archive: "POST",
                             wizardImageUpload: "POST", wizardClearImage: "POST", wizardAutosave: "POST", wizardCreate: "POST"]

    static numbers = ["Zero", "One", 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten', 'Eleven',
                      'Twelve', 'Thirteen', 'Fourteen', 'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen',
                      'Twenty']

    static final LABEL_COLOURS = ["label-success", "label-warning", "label-danger", "label-info", "label-primary", "label-default"]
    public static final int MAX_BACKGROUND_SIZE = 512 * 1024

    def taskService
    def fieldService
    def userService
    def exportService
    def projectService
    def picklistService
    def projectStagingService
    def authService
    def groovyPageRenderer
    Closure<DSLContext> jooqContext

    /**
     * Project home page - shows stats, etc.
     */
    def index() {
        def projectInstance = Project.get(params.id)
        def showTutorial = (params.showTutorial == "true")

        // If the tutorial has been requested but the field is empty, redirect to tutorial index.
        if (showTutorial && Strings.isNullOrEmpty(projectInstance.tutorialLinks)) {
            redirect(controller: "tutorials", action: "index")
            return
        }

        String currentUserId = null

        def username = AuthenticationCookieUtils.getUserName(request)
        if (username) currentUserId = authService.getUserForEmailAddress(username)?.userId

        if (!projectInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        } else {
            // project info
            def userIds = taskService.getUserIdsAndCountsForProject(projectInstance, new HashMap<String, Object>())
            def expedition = grailsApplication.config.expedition
            def roles = [] //  List of Map
            // copy expedition data structure to "roles" & add "members"
            expedition.each {
                def row = it.clone()
                row.put("members", [])
                roles.addAll(row)
            }
            
            userIds.each {
                // iterate over each user and assign to a role.
                def userId = it[0]
                def count = it[1]
                def assigned = false
                def user = User.findByUserId(userId)
                if (user) {
                    roles.eachWithIndex { role, i ->
                        if (count >= role.threshold && role.members.size() < role.max && !assigned) {
                            // assign role
                            def details = userService.detailsForUserId(userId)
                            def userMap = [name: details.displayName, id: user.id, count: count, userId: user.userId]
                            role.get("members").add(userMap)
                            assigned = true
                            log.debug("assigned: " + userId)
                        } else {
                            log.debug("not assigned: " + userId)
                        }
                    }
                }
            }
            log.debug "roles = ${roles as JSON}"

            def leader = roles.find { it.name == "Expedition Leader" } ?.members?.getAt(0)
            def projectSummary = projectService.makeSummaryListFromProjectList([projectInstance], null, null, null, null, null, null, null, null, false)?.projectRenderList?.get(0)

            def taskCount
            def tasksTranscribed
            if (projectSummary) {
                taskCount = projectSummary.taskCount
                tasksTranscribed = projectSummary.transcribedCount
            } else {
                taskCount = Task.countByProject(projectInstance)
                tasksTranscribed = Task.countByProjectAndIsFullyTranscribed(projectInstance, true)
            }

            def percentComplete = (taskCount > 0) ? ((tasksTranscribed / taskCount) * 100) : 0
            if (percentComplete > 99 && taskCount != tasksTranscribed) {
                // Avoid reporting 100% unless the transcribed count actually equals the task count
                percentComplete = 99
            }

            render(view: "index", model: [
                    projectInstance: projectInstance,
                    taskCount: taskCount,
                    tasksTranscribed: tasksTranscribed,
                    roles:roles,
                    currentUserId: currentUserId,
                    leader: leader,
                    percentComplete: percentComplete,
                    projectSummary: projectSummary,
                    transcriberCount: userIds.size(),
                    showTutorial: showTutorial
            ])
        }
    }

    /**
     * REST web service to return a list of tasks with coordinates to show on Google Map
     */
    def tasksToMap() {

        def projectInstance = Project.get(params.id)
        def taskListFields = []

        if (projectInstance) {
            long startQ  = System.currentTimeMillis()
            def taskList = taskService.getFullyTranscribedTasks(projectInstance, [sort:"id", max:999])

            if (taskList.size() > 0) {
                def lats = fieldListToMap(fieldService.getLatestFieldsWithTasks("decimalLatitude", taskList, params))
                def lngs = fieldListToMap(fieldService.getLatestFieldsWithTasks("decimalLongitude", taskList, params))
                def cats = fieldListToMap(fieldService.getLatestFieldsWithTasks("catalogNumber", taskList, params))
                long endQ  = System.currentTimeMillis()
                log.debug("DB query took " + (endQ - startQ) + " ms")
                log.debug("List sizes: task = " + taskList.size() + "; lats = " + lats.size() + "; lngs = " + lngs.size())
                taskList.eachWithIndex { tsk, i ->
                    def jsonObj = [:]
                    jsonObj.put("id",tsk.id)
                    jsonObj.put("filename",tsk.externalIdentifier)
                    jsonObj.put("cat", cats[tsk.id])

                    if (lats.containsKey(tsk.id) && lngs.containsKey(tsk.id)) {
                        jsonObj.put("lat",lats.get(tsk.id))
                        jsonObj.put("lng",lngs.get(tsk.id))
                        taskListFields.add(jsonObj)
                    }
                }

                long endJ  = System.currentTimeMillis()
                log.debug("JSON loop took " + (endJ - endQ) + " ms")
                log.debug("Method took " + (endJ - startQ) + " ms for " + taskList.size() + " records")
            }
            render taskListFields as JSON
        } else {
            // no project found
            render("No project found for id: " + params.id) as JSON
        }
    }

    /**
     * Output list of email addresses for a given project
     */
    def mailingList() {
        def projectInstance = Project.get(params.id)

        if (projectInstance && userService.isAdmin()) {
            def userIds = taskService.getUserIdsForProject(projectInstance)
            log.debug("userIds = " + userIds)
            def userEmails = userService.getEmailAddressesForIds(userIds)
            //render(userIds)
            def list = userEmails.join(";\n")
            render(text:list, contentType: "text/plain")
        }
        else if (projectInstance) {
            render("You do not have permission to access this page.")
        }
        else {
            render("No project found for id: " + params.id)
        }
    }

    /**
     * Utility to convert list of Fields to a Map with task.id as key
     *
     * @param fieldList
     * @return
     */
    private Map fieldListToMap(List fieldList) {
        Map fieldMap = [:]
        fieldList.each {
            if (it.value) {
                fieldMap.put(it.task.id, it.value)
            }
        }

        return fieldMap
    }

    /**
     * Produce an export file
     */
    def exportCSV() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }

        def projectInstance = Project.get(params.id)
        boolean transcribedOnly = params.transcribed?.toBoolean()
        boolean validatedOnly = params.validated?.toBoolean()

        if (projectInstance) {
            def sw = Stopwatch.createStarted()
            def taskList
            if (transcribedOnly) {
                taskList = taskService.getFullyTranscribedTasksAndTranscriptions(projectInstance, [max:9999, sort:"id"])
            } else if (validatedOnly) {
                taskList = taskService.getValidTranscribedTasks(projectInstance, [max:9999, sort:"id"])
            } else {
                taskList = taskService.getAllTasksAndTranscriptionsIfExists(projectInstance, [max: 9999])
            }
            log.debug("Got task list in {}ms", sw.elapsed(MILLISECONDS))
            sw.reset().start()

            def fieldList = fieldService.getAllFieldsWithTasks(taskList)
            log.debug("Got all fields for tasks in {}ms", sw.elapsed(MILLISECONDS))
            sw.reset().start()
            def fieldNames =  ["taskID", "taskURL", "validationStatus", "transcriberID", "validatorID", "externalIdentifier", "exportComment", "dateTranscribed", "dateValidated"]
            fieldNames.addAll(fieldList.name.unique().sort())
            log.debug("Got all field names in {}ms", sw.elapsed(MILLISECONDS))
            sw.reset().start()

            Closure export_func = exportService.export_default
            if (params.exportFormat == 'zip') {
                export_func = exportService.export_zipFile
            }

//            def exporter_func_property = exportService.metaClass.getProperties().find() { it.name == 'export_' + projectInstance.template.name }
//            if (exporter_func_property) {
//                export_func = exporter_func_property.getProperty(exportService)
//            }

            if (export_func) {
                response.setHeader("Cache-Control", "must-revalidate")
                response.setHeader("Pragma", "must-revalidate")
                export_func(projectInstance, taskList, fieldNames, fieldList, response)
                log.debug("Ran export func in {}ms", sw.elapsed(MILLISECONDS))
            } else {
                throw new Exception("No export function for template ${projectInstance.template.name}!")
            }

        }
        else {
            throw new Exception("No project found for id: " + params.id)
        }
    }

    def deleteTasks() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)
        projectService.deleteTasksForProject(projectInstance, true)
        //redirect(action: "edit", id: projectInstance?.id)
        render '', status: SC_ACCEPTED
    }

    def list() {
        params.max = Math.min(params.max ? params.int('max') : 24, 1000)

        params.sort = params.sort ?: session.expeditionSort ? session.expeditionSort : 'completed'

        def projectSummaryList = projectService.getProjectSummaryList(params, false)

        def numberOfUncompletedProjects = projectSummaryList.numberOfIncompleteProjects < numbers.size() ? numbers[projectSummaryList.numberOfIncompleteProjects] : "" + projectSummaryList.numberOfIncompleteProjects

        session.expeditionSort = params.sort

        [
            projects: projectSummaryList.projectRenderList,
            filteredProjectsCount: projectSummaryList.matchingProjectCount,
            numberOfUncompletedProjects: numberOfUncompletedProjects,
            totalUsers: User.countByTranscribedCountGreaterThan(0)
        ]
    }

  /*  def wildlifespotter() {
        def offset = params.getInt('offset', 0)
        def max = Math.min(params.int('max', 24), 1000)
        def sort = params.sort ?: session.expeditionSort ? session.expeditionSort : 'completed'
        def order = params.getOrDefault('sort', 'asc')
        def statusFilterMode = ProjectStatusFilterType.fromString(params?.statusFilter)
        def activeFilterMode = ProjectActiveFilterType.fromString(params?.activeFilter)
        def q = params.q ?: null
        ProjectType pt = ProjectType.findByName('cameratraps')

        def projectSummaryList = projectService.getProjectSummaryList(statusFilterMode, activeFilterMode, q, sort, offset, max, order, pt, false)

        def numberOfUncompletedProjects = projectSummaryList.numberOfIncompleteProjects < numbers.size() ? numbers[projectSummaryList.numberOfIncompleteProjects] : "" + projectSummaryList.numberOfIncompleteProjects;

        def wsi = WildlifeSpotter.instance()

        session.expeditionSort = params.sort

        def model = [
                wildlifeSpotterInstance: wsi,
                projects: projectSummaryList.projectRenderList,
                filteredProjectsCount: projectSummaryList.matchingProjectCount,
                numberOfUncompletedProjects: numberOfUncompletedProjects,
                totalUsers: User.countByTranscribedCountGreaterThan(0)
        ]
        render(view: 'wildlifespotter', model: model)
    } */

    def customLandingPage() {
        String shortUrl = params.shortUrl ?: ''
        def offset = params.getInt('offset', 0)
        def max = Math.min(params.int('max', 24), 1000)
        def sort = params.sort ?: session.expeditionSort ? session.expeditionSort : 'completed'
        def order = params.getOrDefault('sort', 'asc')
        def statusFilterMode = ProjectStatusFilterType.fromString(params?.statusFilter)
        def activeFilterMode = ProjectActiveFilterType.fromString(params?.activeFilter)
        def q = params.q ?: null

        LandingPage landingPage = LandingPage.findByShortUrl(shortUrl)
        if (!landingPage) {
            Long id = params.getLong('id')
            if (id) {
                landingPage = LandingPage.get(id)
            }
        }

        if (!landingPage) {
            if (shortUrl) {
                // if we've accidentally captured an attempt a controller default action, forward that here.
                log.debug("custom landing page caught $shortUrl")
                return forward(controller: shortUrl, params: params)
            } else {
                return redirect(uri: '/')
            }
        }

        ProjectType pt = landingPage.getProjectType()
        def labels = landingPage.label
        def tags = null
        if (labels && labels.size() > 0) {
            tags = labels*.value
        }

        def projectSummaryList = projectService.getProjectSummaryList(statusFilterMode, activeFilterMode, q, sort, offset, max, order, pt, tags, false)

        def numberOfUncompletedProjects = projectSummaryList.numberOfIncompleteProjects < numbers.size() ? numbers[projectSummaryList.numberOfIncompleteProjects] : "" + projectSummaryList.numberOfIncompleteProjects

        session.expeditionSort = params.sort

        def model = [
                landingPageInstance: landingPage,
                projectType: pt.name,
                tags: tags,
                projects: projectSummaryList.projectRenderList,
                filteredProjectsCount: projectSummaryList.matchingProjectCount,
                numberOfUncompletedProjects: numberOfUncompletedProjects,
                totalUsers: User.countByTranscribedCountGreaterThan(0)
        ]
        render(view: 'customLandingPage', model: model)
    }

    /**
     * Redirects a image for the supplied project
     */
    def showImage() {
        def projectInstance = Project.get(params.id)
        if (projectInstance) {
            params.max = 1
            def task = Task.findByProject(projectInstance, params)
            if (task?.multimedia?.filePathToThumbnail) {
                redirect(url: grailsApplication.config.server.url + task?.multimedia?.filePathToThumbnail?.get(0))
            }
        }
    }

    def show() {
        def projectInstance = Project.get(params.id)
        if (!projectInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        } else {
            redirect(action: 'index', id: projectInstance.id, params: params)
        }
    }

    def edit() {
        def currentUser = userService.currentUserId
        if (currentUser != null && userService.isAdmin()) {
            redirect(action: "editGeneralSettings", params: params)
        } else {
            flash.message = "You do not have permission to view this page"
            redirect(controller: "project", action: "index", id: params.id)
        }
    }

    def editGeneralSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.int("id"))
        if (!projectInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        } else {
            final insts = Institution.list()
            final names = insts*.name
            final nameToId = insts.collectEntries { [(it.name): it.id] }
            final labelCats = Label.withCriteria { projections { distinct 'category' } }

            final sortedLabels = projectInstance.labels.sort { a,b -> def x = a.category?.compareTo(b.category); return x == 0 ? a.value <=> b.value : x }
            def counter = 0
            final catColourMap = labelCats.collectEntries { [(it): LABEL_COLOURS[counter++ % LABEL_COLOURS.size()]] }
            return [projectInstance: projectInstance, templates: Template.listOrderByName(), projectTypes: ProjectType.listOrderByName(), institutions: names, institutionsMap: nameToId, labelColourMap: catColourMap, sortedLabels: sortedLabels]
        }
    }

    def checkTemplateSupportMultiTranscriptions() {
        if (!userService.isAdmin()) {
            render (["status": 401, "error": "unauthorized"] as JSON)
        } else {
            def template = Template.findById(params.int("templateId"))
            if (template) {
                render(["supportMultipleTranscriptions": "${template.supportMultipleTranscriptions}"] as JSON)
            } else {
                render(["supportMultipleTranscriptions": "false"] as JSON)
            }
        }
    }

    def editTutorialLinksSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.int("id"))
        if (!projectInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        } else {
            return [projectInstance: projectInstance, templates: Template.list(), projectTypes: ProjectType.list() ]
        }
    }

    def editPicklistSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.int("id"))
        if (!projectInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        } else {
            def picklistInstitutionCodes = [""]
            picklistInstitutionCodes.addAll(picklistService.getInstitutionCodes())

            return [projectInstance: projectInstance, picklistInstitutionCodes: picklistInstitutionCodes ]
        }
    }

    private def getCommonEditSettings(def params) {
        def projectInstance = Project.get(params.int("id"))
        if (!projectInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        } else {
            return [projectInstance: projectInstance ]
        }
    }

    def editMapSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
//        def projectInstance = Project.get(params.int("id"))
//        if (!projectInstance) {
//            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
//            redirect(action: "list")
//        } else {
//            return [projectInstance: projectInstance ]
//        }
        return getCommonEditSettings(params)
    }

    def editBannerImageSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
//        def projectInstance = Project.get(params.int("id"))
//        if (!projectInstance) {
//            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
//            redirect(action: "list")
//        } else {
//            return [projectInstance: projectInstance ]
//        }
        return getCommonEditSettings(params)
    }

    def editBackgroundImageSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
//        def projectInstance = Project.get(params.int("id"))
//        if (!projectInstance) {
//            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
//            redirect(action: "list")
//        } else {
//            return [projectInstance: projectInstance ]
//        }
        return getCommonEditSettings(params)
    }

    def editTaskSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectId = params.long("id")
        def projectInstance = Project.get(projectId)
        if (!projectInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        } else {
            def currentlyLoading = jooqContext.call().fetchExists(TASK_DESCRIPTOR, TASK_DESCRIPTOR.PROJECT_ID.eq(projectId))
            def taskCount = Task.countByProject(projectInstance)
            return [projectInstance: projectInstance, taskCount: taskCount, currentlyLoading: currentlyLoading]
        }
    }

    def updateGeneralSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)
        if (projectInstance) {

            if (params.name) {
                params.featuredLabel = params.name
            }

            final instId = params.getLong("institutionId")
            def inst
            if (instId && (inst = Institution.get(instId))) {
                projectInstance.institution = inst
            } else {
                projectInstance.institution = null
            }

            if (!saveProjectSettingsFromParams(projectInstance, params)) {
                render(view: "editGeneralSettings", model: [projectInstance: projectInstance])
            } else {
                redirect(action:'editGeneralSettings', id: projectInstance.id)
            }
        }  else {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        }
    }

    def update() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)
        if (projectInstance) {
            if (!saveProjectSettingsFromParams(projectInstance, params)) {
                render(view: "editGeneralSettings", model: [projectInstance: projectInstance])
            } else {
                redirect(action:'editGeneralSettings', id: projectInstance.id)
            }
        }  else {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        }
    }

    def updateTutorialLinksSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)
        if (projectInstance) {
            if (!saveProjectSettingsFromParams(projectInstance, params)) {
                render(view: "editTutorialLinksSettings", model: [projectInstance: projectInstance])
            } else {
                redirect(action:'editTutorialLinksSettings', id: projectInstance.id)
            }
        }  else {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        }
    }

    def deleteAllTasksFragment() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.int("id"))
        def taskCount = Task.countByProject(projectInstance)
        [projectInstance: projectInstance, taskCount: taskCount]
    }

    def deleteProjectFragment() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.int("id"))
        def taskCount = Task.countByProject(projectInstance)
        [projectInstance: projectInstance, taskCount: taskCount]
    }

    private boolean saveProjectSettingsFromParams(Project projectInstance, GrailsParameterMap params) {
        if (projectInstance) {
            if (params.version) {
                def version = params.version.toLong()
                if (projectInstance.version > version) {
                    projectInstance.errors.rejectValue("version", "default.optimistic.locking.failure", [message(code: 'project.label', default: 'Project')] as Object[], "Another user has updated this Project while you were editing")
                    return false
                }
            }

            // Issue #371 - Activation notification
            def oldInactiveFlag = projectInstance.inactive
            boolean newInactive = (params.inactive != null ? params.inactive == "true" : projectInstance.inactive)

            projectInstance.properties = params

            if (!projectInstance.template.supportMultipleTranscriptions) {
                projectInstance.transcriptionsPerTask = Project.DEFAULT_TRANSCRIPTIONS_PER_TASK
                projectInstance.thresholdMatchingTranscriptions = Project.DEFAULT_THRESHOLD_MATCHING_TRANSCRIPTIONS
            }

            if (!projectInstance.hasErrors() && projectService.saveProject(projectInstance)) {
                if (((oldInactiveFlag != newInactive) && (!newInactive))) {
                    log.info("Project was activated; Sending project activation notification")
                    def message = groovyPageRenderer.render(view: '/project/projectActivationNotification', model: [projectName: projectInstance.name])
                    projectService.emailNotification(projectInstance, message, ProjectService.NOTIFICATION_TYPE_ACTIVATION)
                }
                flash.message = "Expedition updated"
                return true
            } else {
                flash.message = "Expedition update failed"
            }
        }
        return false
    }

    def updatePicklistSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)
        if (projectInstance) {
            if (!saveProjectSettingsFromParams(projectInstance, params)) {
                render(view: "editPicklistSettings", model: [projectInstance: projectInstance])
            } else {
                redirect(action:'editPicklistSettings', id: projectInstance.id)
            }
        }  else {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        }
    }

    def delete() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)
        if (projectInstance) {
            try {
                projectService.deleteProject(projectInstance)
                flash.message = "${message(code: 'default.deleted.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
                redirect(action: "list")
            }
            catch (DataIntegrityViolationException e) {
                String message = "${message(code: 'default.not.deleted.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
                flash.message = message
                log.error(message, e)
                redirect(action: "show", id: params.id)
            }
        }
        else {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'project.label', default: 'Project'), params.id])}"
            redirect(action: "list")
        }
    }
    
    def uploadFeaturedImage() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)

        if(request instanceof MultipartHttpServletRequest) {
            MultipartFile f = ((MultipartHttpServletRequest) request).getFile('featuredImage')
            
            if (f != null && f.size > 0) {

                def allowedMimeTypes = ['image/jpeg', 'image/png']
                if (!allowedMimeTypes.contains(f.getContentType())) {
                    flash.message = "Image must be one of: ${allowedMimeTypes}"
                    render(view:'editBannerImageSettings', model:[projectInstance:projectInstance])
                }

                try {
                    def filePath = "${grailsApplication.config.images.home}/project/${projectInstance.id}/expedition-image.jpg"
                    def file = new File(filePath)
                    file.getParentFile().mkdirs()
                    f.transferTo(file)
                    projectService.checkAndResizeExpeditionImage(projectInstance)
                } catch (Exception ex) {
                    flash.message = "Failed to upload image: " + ex.message
                    log.error("Failed to upload image: " + ex.message, ex)
                    render(view:'editBannerImageSettings', model:[projectInstance:projectInstance])
                    return
                }
            }
        }

        projectInstance.featuredImageCopyright = params.featuredImageCopyright
        projectService.saveProject(projectInstance)
        flash.message = "Expedition image settings updated."
        redirect(action: "editBannerImageSettings", id: params.id)
    }

    def uploadBackgroundImage() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.id)

        if(request instanceof MultipartHttpServletRequest) {
            MultipartFile f = ((MultipartHttpServletRequest) request).getFile('backgroundImage')

            if (f != null && f.size > 0) {

                def allowedMimeTypes = ['image/jpeg', 'image/png']
                if (!allowedMimeTypes.contains(f.getContentType())) {
                    flash.message = "Image must be one of: ${allowedMimeTypes}"
                    render(view:'editBackgroundImageSettings', model:[projectInstance:projectInstance])
                }

                if (f.size >= MAX_BACKGROUND_SIZE) {
                    flash.message = "Image size cannot be bigger than 512 KB (half a MB)"
                    render(view:'editBackgroundImageSettings', model:[projectInstance:projectInstance])
                }

                try {
                    f.inputStream.withCloseable {
                        projectInstance.setBackgroundImage(it, f.contentType)
                    }
                } catch (Exception ex) {
                    flash.message = "Failed to upload image: " + ex.message
                    log.error("Failed to upload image: " + ex.message, ex)
                    render(view:'editBackgroundImageSettings', model:[projectInstance:projectInstance])
                }
            }
        }

        projectInstance.backgroundImageAttribution = params.backgroundImageAttribution
        projectInstance.backgroundImageOverlayColour = params.backgroundImageOverlayColour
        projectService.saveProject(projectInstance)
        flash.message = "Background image settings updated."
        redirect(action: "editBackgroundImageSettings", id: params.id)
    }

    def clearBackgroundImageSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        Project projectInstance = Project.get(params.id)
        if (projectInstance) {
            projectInstance.backgroundImageAttribution = null
            projectInstance.backgroundImageOverlayColour = null
            projectInstance.setBackgroundImage(null,null)
        }

        flash.message = "Background image settings have been deleted."
        redirect(action: "editBackgroundImageSettings", id: params.id)
    }

    def updateMapSettings() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def projectInstance = Project.get(params.int("id"))
        if (projectInstance) {
            def showMap = params.showMap == "on"
            def zoom = params.int("mapZoomLevel")
            def latitude = params.double("mapLatitude")
            def longitude = params.double("mapLongitude")

            projectInstance.showMap = showMap

            if (zoom && latitude && longitude) {
                projectInstance.mapInitZoomLevel = zoom
                projectInstance.mapInitLatitude = latitude
                projectInstance.mapInitLongitude = longitude
            }
            flash.message = "Map settings updated"
            projectService.saveProject(projectInstance, true, true)
        }

        redirect(action: 'editMapSettings', id: projectInstance?.id)
    }

    def findProjectFragment() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        render(view: 'findProjectFragment')
    }

    def findProjectResultsFragment() {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        def q = params.q as String ?: ""

        def c = Project.createCriteria()
        def projectList = c.list {
            or {
                ilike("name", "%${q}%")
                ilike("featuredOwner", "%${q}%")
                ilike("featuredLabel", "%${q}%")
                ilike("shortDescription", "%${q}%")
                ilike("description", "%${q}%")
            }
        }

        [projectList: projectList]
    }

    def addLabel(Project projectInstance) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        def labelId = params.labelId
        def label = Label.get(labelId)
        if (!label) {
            render status: 404
            return
        }

        projectInstance.addToLabels(label)
        projectService.saveProject(projectInstance, true)

        // Just adding a label won't trigger the GORM update event, so force a project update
        DomainUpdateService.scheduleProjectUpdate(projectInstance.id)
        render status: 204
    }

    def removeLabel(Project projectInstance) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        def labelId = params.long('labelId')
        def label = Label.get(labelId)
        if (!label) {
            render status: 404
            return
        }

        projectInstance.removeFromLabels(label)
        projectService.saveProject(projectInstance, true)

        // Just adding a label won't trigger the GORM update event, so force a project update
        DomainUpdateService.scheduleProjectUpdate(projectInstance.id)
        render status: 204
    }

    def newLabels(Project projectInstance) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        def term = params.term ?: ''
        def ilikeTerm = "%${term.replace('%','')}%"
        def existing = projectInstance?.labels
        def labels

        if (existing) {
            def existingIds = existing*.id.toList()
            labels = Label.withCriteria {
                or {
                    ilike 'category', ilikeTerm
                    ilike 'value', ilikeTerm
                }
                not {
                    inList 'id', existingIds
                }
            }
        } else {
            labels = Label.findAllByCategoryIlikeOrValueIlike(ilikeTerm, ilikeTerm)
        }

        render labels as JSON
    }

    def wizard(String id) {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        if (!id) {
            def stagingId = UUID.randomUUID().toString()
            projectStagingService.ensureStagingDirectoryExists(stagingId)
            redirect(action: 'wizard', id: stagingId)
            return
        }

        if (!projectStagingService.stagingDirectoryExists(id)) {
            // this one has probably been cancelled or saved already
            redirect(action: 'wizard')
            return
        }

        def project = new NewProjectDescriptor(stagingId: id)

        def list = Institution.list()
        def institutions = list.collect { [id: it.id, name: it.name ] }
        def templates = Template.listOrderByName([:])
        def projectTypes = ProjectType.listOrderByName([:])
        def projectImageUrl = projectStagingService.hasProjectImage(project) ? projectStagingService.getProjectImageUrl(project) : null
        def labels = Label.list()
        def autosave = projectStagingService.getTempProjectDescriptor(id)

        def c = PicklistItem.createCriteria()
        def picklistInstitutionCodes = c {
            isNotNull("institutionCode")
            projections {
                distinct("institutionCode")
            }
            order('institutionCode')
        }


        final labelCats = Label.withCriteria { projections { distinct 'category' } }

        def counter = 0
        final catColourMap = labelCats.collectEntries { [(it): LABEL_COLOURS[counter++ % LABEL_COLOURS.size()]] }

        [
                stagingId: id,
                institutions: institutions,
                templates: templates,
                projectTypes: projectTypes,
                projectImageUrl: projectImageUrl,
                labels: labels,
                autosave: autosave,
                picklists: picklistInstitutionCodes,
                labelColourMap: catColourMap
        ]
    }

    def wizardAutosave(String id) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }
        projectStagingService.saveTempProjectDescriptor(id, request.reader)
        render status: 204
    }

    def wizardImageUpload(String id) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        def project = new NewProjectDescriptor(stagingId: id)

        def errors = []
        def errorStatus = SC_BAD_REQUEST
        def result = ""

        if (request instanceof MultipartHttpServletRequest) {
            MultipartFile f = ((MultipartHttpServletRequest) request).getFile('image')
            if (f != null && f.size > 0) {
                final allowedMimeTypes = ['image/jpeg', 'image/png']
                if (!allowedMimeTypes.contains(f.getContentType())) {
                    errors << "Image must be one of: ${allowedMimeTypes}"
                    errorStatus = SC_UNSUPPORTED_MEDIA_TYPE
                } else {
                    if (params.type == 'backgroundImageUrl') {
                        if (f.size > MAX_BACKGROUND_SIZE) {
                            errors << "Background image must be less than 512KB"
                            errorStatus = SC_REQUEST_ENTITY_TOO_LARGE
                        } else {
                            projectStagingService.uploadProjectBackgroundImage(project, f)
                            result = projectStagingService.getProjectBackgroundImageUrl(project)
                        }
                    } else {
                        projectStagingService.uploadProjectImage(project, f)
                        result = projectStagingService.getProjectImageUrl(project)
                    }
                }
            } else {
                errors << "No file provided?!"
            }
        }

        if (errors) {
            response.status = errorStatus
            render(errors as JSON)
        } else {
            render([imageUrl: result] as JSON)
        }
    }

    def wizardClearImage(String id) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        def project = new NewProjectDescriptor(stagingId: id)
        def type = request.getJSON()?.type ?: ''
        if (type == 'background') {
            projectStagingService.clearProjectBackgroundImage(project)
        } else {
            projectStagingService.clearProjectImage(project)
        }
        render status: 204
    }

    def wizardProjectNameValidator(String name) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        render([ count: Project.countByName(name) ] as JSON)
    }

    def wizardCancel(String id) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        projectStagingService.purgeProject(new NewProjectDescriptor(stagingId: id))
        redirect(controller:'admin', action:"index")
    }

    def wizardCreate(String id) {
        if (!userService.isAdmin()) {
            render status: 401
            return
        }

        if (!userService.isAdmin()) {
            response.sendError(SC_FORBIDDEN, "you don't have permission")
        }
        try {
            def body = request.getJSON()
            body.createdBy = userService.getCurrentUserId()
            def descriptor = NewProjectDescriptor.fromJson(id, body)

            log.debug("Attempting to create project with descriptor: $descriptor")

            def projectInstance = projectStagingService.createProject(descriptor)
            if (!projectInstance) {
                render status: 400
            } else {
                response.status = 201
                def obj = [id: projectInstance.id] as JSON
                render(obj)
            }
        } finally {
            projectStagingService.purgeProject(new NewProjectDescriptor(stagingId: id))
        }
    }

    def archiveList() {
        final sw = Stopwatch.createStarted()
        if (!userService.isAdmin()) {
            response.sendError(SC_FORBIDDEN, "you don't have permission")
            return
        }

        if (!params.sort) {
            params.sort = 'id'
            params.order = 'asc'
        }
        if (!params.max) {
            params.max = 20
        }

        def projects
        def total
        def institution
        if (params.institution) {
            institution = Institution.get(params.long('institution'))
        }

        if (institution && !Strings.isNullOrEmpty(params.q)) {
            if (institution) {
                projects = Project.findAllByArchivedAndInstitutionAndNameIlike(false, institution, "%${params.q}%", params)
                total = Project.countByArchivedAndInstitutionAndNameIlike(false, institution, "%${params.q}%")
            } else {
                projects = null
                total = 0
            }
        } else if (institution) {
            if (institution) {
                projects = Project.findAllByArchivedAndInstitution(false, institution, params)
                total = Project.countByArchivedAndInstitution(false, institution)
            } else {
                projects = null
                total = 0
            }
        } else if (!Strings.isNullOrEmpty(params.q)) {
            projects = Project.findAllByArchivedAndNameIlike(false, "%${params.q}%", params)
            total = Project.countByArchivedAndNameIlike(false, "%${params.q}%")
        } else {
            projects = Project.findAllByArchived(false, params)
            total = Project.countByArchived(false)
        }
        sw.stop()
        log.debug("archiveList: findAllByArchived = $sw")
//        sw.reset().start()
        //def total = Project.countByArchived(false)
//        sw.stop()
//        log.debug("archiveList: countByArchived = $sw")
//        sw.reset().start()
//        def sizes = projectService.projectSize(projects)
//        sw.stop()
//        log.debug("archiveList: projectSize = $sw")
        sw.reset().start()
        def completions = projectService.calculateCompletion(projects)
        sw.stop()
        log.debug("archiveList: calculateCompletion = $sw")
        sw.reset().start()

        List<ArchiveProject> projectsWithSize = projects.collect {
            final counts = completions[it.id]
            final transcribed
            final validated
            if (counts) {
                transcribed = (counts.transcribed / counts.total) * 100.0
                validated = (counts.validated / counts.total) * 100.0
            } else {
                transcribed = 0.0
                validated = 0.0
            }
            new ArchiveProject(project: it, /*size: sizes[it.id].size,*/ percentTranscribed: transcribed, percentValidated: validated)
        }

        respond(projectsWithSize, model: ['archiveProjectInstanceListSize': total,
                                          'imageStoreStats': projectService.imageStoreStats()])
    }

    def projectSize(Project project) {
        def size = [size: FileUtils.byteCountToDisplaySize(projectService.projectSize(project).size)]
        respond(size)
    }

    /**
     * Archive project controller action. Archives a project or returns an error if not allowed.
     * @param project the project to archive.
     */
    def archive(Project project) {
        if (!userService.isAdmin()) {
            log.error("Unauthorised access by ${userService.getCurrentUser()?.displayName}")
            redirect(uri: "/")
            return
        }

        try {
            projectService.archiveProject(project)
            log.debug("${project.name} (id=${project.id}) archived")
            flash.message = "${message(code: 'project.label', default: 'Project')} ${project.name} archived."
            redirect(action: 'archiveList', params: params)
        } catch (e) {
            flash.message = "An error occured while archiving ${project.name}."
            log.error("An error occured while archiving ${message(code: 'project.label', default: 'Project')} ${project}", e)
            redirect(action: 'archiveList', params: params)
        }
    }

    def downloadImageArchive(Project project) {
        if (!userService.isAdmin()) {
            response.sendError(SC_FORBIDDEN, "you don't have permission")
            return
        }
        response.contentType = 'application/zip'
        response.setHeader('Content-Disposition', "attachment; filename=\"${project.id}-${project.name}-images.zip\"")
        final os = response.outputStream
        try {
            projectService.writeArchive(project, os)
        } catch (e) {
            log.error("Exception while creating image archive for $project", e)
            //os.close()
        }
    }

    def summary() {
        /*
        {
          "project": "Name of project or expidition",
          "contributors": "Number of individual users",
          "numberOfSubjects": "Number of total assets/specimens/subjects",
          "percentComplete": "0-100",
          "firstContribution": "UTC Timestamp",
          "lastContribution": "UTC Timestamp"
        }
         */
        final Project project
        def id = params.id
        if (id.isLong()) {
            project = Project.get(id as Long)
        } else {
            project = Project.findByName(id)
        }

        if (!project) {
            response.sendError(404, "project not found")
            return
        }

        def completions = projectService.calculateCompletion([project])[project.id]
        def numberOfSubjects = completions?.total
        def percentComplete = numberOfSubjects > 0 ? ((completions?.transcribed as Double) / ((numberOfSubjects ?: 1.0) as Double)) * 100.0 : 0.0
        def contributors = projectService.calculateNumberOfTranscribers(project)
        def dates = projectService.calculateStartAndEndTranscriptionDates(project)

        def result = [
                project: project.name,
                contributors: contributors,
                numberOfSubjects: numberOfSubjects,
                percentComplete: percentComplete,
                firstContribution: dates?.start,
                lastContribution: dates?.end
        ]

        respond result
    }

    def loadProgress(Project projectInstance) {
        if (!userService.isAdmin()) {
            redirect(uri: "/")
            return
        }
        respond projectInstance
    }
}
