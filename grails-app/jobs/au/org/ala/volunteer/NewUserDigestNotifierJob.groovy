package au.org.ala.volunteer

import grails.util.Environment
import grails.util.Holders
import groovy.sql.Sql

import javax.sql.DataSource

class NewUserDigestNotifierJob {
    def userService
    def mailService
    DataSource dataSource
    def concurrent = false

    def description = "Notify admin users about new users who have completed their first five transcriptions"

    static triggers = {
        if (Environment.current == Environment.DEVELOPMENT && Holders.config.getProperty('digest.debug', Boolean,false)) {
            log.debug("Enabling 30s trigger")
            cron name: 'newUsersDigestTrigger', cronExpression: '/30 * * * * ?' // every 30s
        } else {
            log.debug("Enabling 6am trigger")
            cron name: 'newUsersDigestTrigger', cronExpression: '0 0 6 * * ?' // 6:00am
        }
    }

    def execute() {
        if (grailsApplication.config.getProperty('digest.enabled', Boolean, false)) {
            /* Configure properties file like this: digest.address=email1,email2 */
            List recipient = grailsApplication.config.getProperty('digest.address', List, [])
            int threshold = 1 // day
            String fromAddress = grailsApplication.config.getProperty('grails.mail.default.from',
                    "DigiVol <noreply@volunteer.ala.org.au>")
            if (!recipient) {
                throw new IllegalStateException("New user transcriptions digest email is enabled but no email address " +
                        "(digest.address) was specified")
            }
            if (threshold <= 0) {
                throw new IllegalStateException("New user transcriptions digest email is enabled but threshold " +
                        "(digest.threshold) has been configured with an invalid value")
            }
            log.info("New User Digest Notifier job starting at ${new Date()}")
            try {
                def sql = new Sql(dataSource)
                String query = """\
                    select u.id, u.created, count(date_fully_transcribed) as numTranscriptions
                    from vp_user u
                    left join transcription t on t.fully_transcribed_by = u.user_id
                    where created >= (current_timestamp - interval '${threshold} day')
                    group by u.id, u.created
                    order by created desc;""".stripIndent()

                def userList = []
                sql.eachRow(query) { row ->
                    User user = User.get(row.id as long)
                    if (user) {
                        userList.add([user: user, transcribeCount: row.numTranscriptions])
                    }
                }

                if (userList) {
                    log.info("Found ${userList.size()} user ids for new user digest")
                    log.debug("Emailling $recipient with new user digest.")
                    mailService.sendMail {
                        from fromAddress
                        to recipient
                        subject "DigiVol: New Transcribers"
                        body(view:"/mail/newTranscribers",
                                model: [newTranscribers: userList, threshold: threshold])
                    }
                } else {
                    log.debug("No new users found for digest")
                }
             } catch (Exception e) {
                log.error("Update users job failed with exception", e)
            }
        }
        log.info("New User Digest Notifier job finishing at ${new Date()}")
    }
}
