package au.org.ala.volunteer

import com.google.common.base.Strings
import com.google.common.io.Resources
import grails.core.GrailsApplication
import grails.converters.JSON
import grails.transaction.Transactional
import grails.util.Environment
import groovy.sql.Sql

import java.util.regex.Pattern

class TemplateService {

    GrailsApplication grailsApplication
    def userService
    def dataSource

    @Transactional
    def cloneTemplate(Template template, String newName) {
        def newTemplate = new Template(name: newName, viewName: template.viewName, author: userService.currentUser.userId)

        newTemplate.viewParams = [:]
        template.viewParams.entrySet().each { entry ->
            newTemplate.viewParams[entry.key] = entry.value
        }

        newTemplate.viewParams2 = JSON.parse((template.viewParams2 as JSON).toString()) as Map

        newTemplate.save()
        // Now we need to copy over the template fields
        def fields = TemplateField.findAllByTemplate(template)
        Field.saveAll(fields.collect { f ->
            def newField = new TemplateField(f.properties)
            newField.template = newTemplate
            newField
        })
    }

    /**
     * Returns a list of the currently used views in the template table (used for search filtering).
     * @return
     */
    def getTemplateViews() {
        def query = Template.createCriteria()
        def views = query.listDistinct {
            projections {
                groupProperty("viewName")
            }
            order ('viewName', 'asc')
        }

        return views
    }

    def getAvailableTemplateViews() {
        def views = []
        def pattern

        if (Environment.isDevelopmentEnvironmentAvailable()) {
            log.debug("Checking for dev templates")
            findDevGsps 'grails-app/views/transcribe/templateViews', views
            // This is a pattern for windows... linux developer would have to modify?
            pattern = Pattern.compile("^grails-app\\\\views\\\\transcribe\\\\templateViews\\\\(.*Transcribe)[.]gsp\$")
        } else {
            log.debug("Checking for WAR deployed templates")
            findWarGsps '/WEB-INF/grails-app/views/transcribe/templateViews', views
            pattern = Pattern.compile("^transcribe/templateViews/(.*Transcribe)[.]gsp\$")
        }

        log.debug("Got views: ${views}")

        def results = views.collectMany { String viewName ->
            def m = pattern.matcher(viewName)
            m.matches() ? [m.group(1)] : []
        }.sort()

        log.debug("Views after collect/sort: {}", results)
        return results
    }

    /**
     * Determines whether the user can edit the provided template. If the user is an institution admin for the institution
     * owning the project (or a site admin), they can edit, else it is read only.
     * @param template
     * @param user
     * @return true if the user can edit the template.
     */
    def getTemplatePermissions(Template template) {
        def templatePermissions = [template: template, canEdit: userService.isSiteAdmin()]

        if (!userService.isSiteAdmin()) {
            // List of Institutions user is Institution Admin for
            def institutionAdminList = userService.getAdminInstitutionList()*.id
            log.debug("template permissions: institution ID list: ${institutionAdminList}")
            def projectInstitutionList = template.projects*.institution?.id?.unique()
            log.debug("template permissions: project institution ID list: ${projectInstitutionList}")

            // If an existing template is used by multiple institutions, it can no longer be edited.
            if (projectInstitutionList.size() == 1) {
                templatePermissions.canEdit = !institutionAdminList.intersect(projectInstitutionList).isEmpty()
            } else {
                templatePermissions.canEdit = false
            }
        }
        log.debug("Can edit: ${templatePermissions.canEdit}")
        return templatePermissions
    }

    /**
     * Returns a list of templates available for the currently logged in user.
     * If that user is an Institution Admin, the list includes templates used by their projects and global templates but
     * not hidden templates.
     * @return a list of available templates.
     */
    def getTemplatesForUser() {
        if (userService.isSiteAdmin()) {
            return Template.listOrderByName()
        } else {
            def institutionAdminList = userService.getAdminInstitutionList()*.id.unique()
            def institutionIdList = institutionAdminList.join(",")
            def results = []

            def query = """\
                select t.id, t.name
                from template t
                where (t.id in (
                    select distinct p.template_id
                    from project p
                    where p.institution_id in (${institutionIdList}))
                 or t.is_global = true)
                and t.is_hidden = false 
                order by t.name """.stripIndent()

            def sql = new Sql(dataSource)
            sql.eachRow(query) { row ->
                Template template = Template.get(row.id)
                results.add(template)
            }

            sql.close()
            return results
        }
    }

    /**
     * Returns a list of templates available for an institution. Includes templates previously used by the institution
     * and any global templates.
     * @param institution the institution
     * @param includeHidden determines if the query excludes hidden templates.
     * @return a list of available templates
     */
    def getTemplatesForInstitution(Institution institution, boolean includeHidden = false) {
        def results = []
        def includeHiddenClause = (!includeHidden ? " and template.is_hidden = false " : "")

//        def query = """\
//                select t.id, t.name
//                from template t
//                where (t.id in (
//                    select distinct p.template_id
//                    from project p
//                    where p.institution_id = (:institutionId))
//                 or t.is_global = true)
//                ${includeHiddenClause}
//                order by t.name """.stripIndent()
        def query = """\
            select distinct template.id, template.name, template.is_global, template.is_hidden
            from template 
            left outer join project on (project.template_id = template.id)
            left outer join institution on (institution.id = project.institution_id)
            where (institution.id = :institutionId
                or template.is_global = true
                or institution.id is null)
                ${includeHiddenClause}
            order by template.name """.stripIndent()

        def sql = new Sql(dataSource)
        sql.eachRow(query, [institutionId: institution?.id]) { row ->
            Template template = Template.get(row.id)
            results.add(template)
        }

        sql.close()

        return results
    }

    /**
     * Returns a list of Templates. Can be filtered with the following filters: <br />
     * <li>institution: (int) returns templates belonging to the institution with an ID of this value.
     * <li>q: (string) returns templates where the attached project name includes this string in the name (case-insensitive)
     * <li>viewName: (string) returns templates using this view name.
     * @param params Hashmap containing parameters (see above). Other parameters include max, offset, sort and order.
     * @return the list of templates as per the filter query.
     */
    def getTemplatesWithFilter(Map params) {
        def results = []
        Institution institution

        def query = """\
            select distinct t.id as template_id, 
                t.name, 
                u.last_name || ', ' || u.first_name as author, 
                t.author as author_user_id, 
                t.view_name
            from template t 
            left outer join project p on (p.template_id = t.id) 
            left outer join institution i on (i.id = p.institution_id) 
            left join vp_user u on (u.user_id = t.author) """.stripIndent()

        def clause = []
        def parameters = [:]

        // If the institution filter parameter isn't set, check if user is an institution admin and default to their
        // institution
//        if (Strings.isNullOrEmpty(params.institution) && (userService.isInstitutionAdmin() && !userService.isSiteAdmin())) {
//            institution = userService.getAdminInstitutionList().first()
//        }

        // Add the institution filter, if present
        if (!Strings.isNullOrEmpty(params.institution) && params.institution != 'all') {
            institution = Institution.get(params.institution)
            clause.add(" i.id = :institutionId ")
            parameters.institutionId = institution.id
        }

        // Add the project search filter, if present
        if (!Strings.isNullOrEmpty(params.q)) {
            clause.add(" p.name ilike '%${params.q}%' ")
        }

        // Add the view name filter, if present
        if (!Strings.isNullOrEmpty(params.viewName)) {
            clause.add(" t.view_name = :viewName ")
            parameters.viewName = params.viewName
        }

        if (!userService.isSiteAdmin()) {
            clause.add(" t.is_hidden = false ")
        }

        clause.eachWithIndex { line, idx ->
            if (idx == 0) query += " where "
            else query += " and "
            query += "${line}"
        }

        if (!Strings.isNullOrEmpty(params.sort)) {
            String sort
            switch (params.sort) {
                case 'name': sort = "t.name"
                    break
                case 'viewName': sort = "t.view_name"
                    break
                case 'author': sort = "author"
                    break
                default: sort = "t.id"
                    break
            }
            query += " order by ${sort} ${params.order}"
        }

        def sql = new Sql(dataSource)
        sql.eachRow(query, parameters, params.offset, params.max) { row ->
            Template template = Template.get(row.template_id)
            results.add(template)
        }

        def countQuery = "select count(*) as row_count_total from (" + query + ") as countQuery"
        def countRows = sql.firstRow(countQuery, parameters)

        def returnMap = [templateList: results, totalCount: countRows.row_count_total]

        sql.close()
        return returnMap
    }

    private void findDevGsps(String current, List gsps) {
        for (file in new File(current).listFiles()) {
            if (file.path.endsWith('.gsp')) {
                gsps << file.path - 'grails-app/views/'
            } else {
                findDevGsps file.path, gsps
            }
        }
    }

    private void  findWarGsps(String current, List<String> gsps) {
        try {
            def properties = Resources.getResource('/gsp/views.properties').withReader('UTF-8') { r ->
                def p = new Properties()
                p.load(r)
                p
            }
            def keys = properties.keySet()
            log.debug("Got keys from views.properties {}", keys)
            keys.findAll { it.toString().startsWith(current) }.collect(gsps) { it - '/WEB-INF/grails-app/views/' }
        } catch (e) {
            log.error("Error loading views.properties!", e)
        }
    }


}
