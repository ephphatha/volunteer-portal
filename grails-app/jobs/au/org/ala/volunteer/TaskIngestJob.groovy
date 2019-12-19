package au.org.ala.volunteer

class TaskIngestJob {

    def concurrent = false

    def description = "Background job to load tasks into the database"

    def taskLoadService

    static triggers = {
        simple name: 'simpleTrigger', startDelay: 10000, repeatInterval: 30000
    }

    def execute(context) {
        log.debug("Executing TaskIngestJob")
        def projectId = context.mergedJobDataMap.get('project')
        try {
            taskLoadService.doTaskLoad(projectId)
        } catch (Exception e) {
            log.error("Task Ingest job failed with exception", e)
        }
        log.debug("Task Ingest job finishing at ${new Date()}")
    }
}
