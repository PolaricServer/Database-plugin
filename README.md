## Database plugin for Polaric Server

The "Polaric Server" is mainly a web based service to present (APRS) 
tracking information on maps and where the information is updated in real-
time. It is originally targeted for use by radio amateurs in voluntary search
and rescue service in Norway. It consists of a web application and a server 
program (APRS daemon). 
 
This is a _plugin_ that provides database storage using PostgreSQL. It is 
optional and is primarily meant for online servers having sufficient 
memory and storage. It is used on aprs.no. 

### Features

APRS positions (spatiotemporal data) and APRS packets for later analysis.
It is configurable what callsigns are stored and for how long. Queries 
include movement trails, positions covered by digipeaters, etc. 

Client/user-owned data like trackers, static position objects, 
map-extents, map-layer setups, etc through a REST API.

Future uses may include (SAR) mission information, users, etc. 

## System requirements

Linux/Java platform (tested on Debian/Ubuntu platforms) with
* Java Runtime environment version 11 or later. 
* scala-library version 2.11 or later. You will also need scala-xml
  and scala-parser-combinators packages. 
* polaric-aprsd installed.

## Installation and use

A deb package will be available soon. It has scripts that can install and configure 
the database. It depends on PostgreSQL and PostGIS packages. 

When installed for the first time, run the script 'polaric-dbsetup'
It will install database software and the PostGIS extension, it will create
the necessary tables and it will configure polaric-aprsd to use the
plugin. 

Please edit the plugin configuration to suit your needs: /etc/polaric-aprsd/config.d/database.ini
It is fairly self-explained. Database name is 'polaric' and is owned by a database user 'polaric'. 
Remember to run polaric-restart after installing the plugin or making changes to the config file. 
 
You may use 'psql' to inspect and maintain the database. 
 
You may also build it yourself. To do this, install the source code for polaric-aprsd
and make a symlink to its 'lib' subdirectory and polaric-aprsd.jar. To build a deb 
package run the command:

debuild -uc -us -b


