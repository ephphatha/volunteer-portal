# DigiVol   [![Build Status](https://travis-ci.org/AtlasOfLivingAustralia/volunteer-portal.svg?branch=develop)](https://travis-ci.org/AtlasOfLivingAustralia/volunteer-portal)

The [Atlas of Living Australia], in collaboration with the [Australian Museum], developed [DigiVol]
to harness the power of online volunteers (also known as crowdsourcing) to digitise biodiversity data that is locked up
in biodiversity collections, field notebooks and survey sheets.

## Running

The ansible inventories are currently out of date. Manually running the application is the only supported method at this time.

### Manual
You can run DigiVol manually by using gradle to build:

```bash
./gradlew assemble
java -jar build/libs/volunteer-portal-*.war
open http://devt.ala.org.au:8080/
```

or by using the grails built-in server:
```bash
sdk env
grails run-app
```

#### Preconditions
Install postgres and create a database called `volunteers` for DigiVol. Optionally create a new local user and postgres user to act as the database owner. The baseline migration script requires that this user has superuser privileges, so if making a new user ensure it is granted that access with `alter role <digivol> superuser;`. You will also need to change the username/password used to connect to the database in application.yml

Set a password on the database user (either postgres or the user created above), then copy local.properties.template as local.properties and change the username/password (and url if required).

You will also need to ensure `/data/volunteer/` and `/data/volunteer-portal/` exist and are writable by whichever user account you use to run the grails application.

In order to authenticate and use the application you need to either run your own CAS auth server or set up a local webserver/proxy to handle requests for devt.ala.org.au (if using the default auth server of auth.ala.org.au). This can be accomplished by using nginx (installed on localhost) and a site configuration based on devt.ala.org.au.template

### Ansible
~~To run up a vagrant instance of DigiVol you can use the volunteer_portal_instance ansible playbook from the
[AtlasOfLivingAustralia/ala-install] repository.  This will deploy a pre-compiled version from the ALA Maven repository.~~

~~*NOTE: Both [vagrant] and [ansible] must be installed first.*~~

~~Then setup the VM and run the playbook:~~

```bash
git clone https://github.com/AtlasOfLivingAustralia/ala-install.git
cd ala-install/vagrant/ubuntu-trusty
vagrant up
cd ../../ansible
ansible-playbook -i inventories/vagrant --user vagrant --private-key ~/.vagrant.d/insecure_private_key --sudo volunteer-portal.yml
```

~~Deploying to a server can be done similarly, though you will need to define an ansible inventory first.~~

## Contributing

DigiVol is a [Grails] v3.2.4 based web application.  It requires [PostgreSQL] for data storage.  Development follows the 
[git flow] workflow.

For git flow operations you may like to use the `git-flow` command line tools.  Either install [Atlassian SourceTree]
which bundles its own version or install them via:

```bash
# OS X
brew install git-flow
# Ubuntu
apt-get install git-flow
```

[Atlas of Living Australia]: http://www.ala.org.au/
[Australian Museum]: http://australianmuseum.net.au/
[PostgreSQL]: http://postgres.org/
[DigiVol]: http://volunteer.ala.org.au/
[Grails]: http://www.grails.org/
[git flow]: https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow "Gitflow Workflow"
[Atlassian SourceTree]: http://www.sourcetreeapp.com/
[AtlasOfLivingAustralia/ala-install]: https://github.com/AtlasOfLivingAustralia/ala-install
[vagrant]: https://www.vagrantup.com/
[ansible]: http://www.ansible.com/home
