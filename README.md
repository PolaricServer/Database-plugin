## Database plugin for Polaric Server

The "Polaric Server" is mainly a web based service to present (APRS) 
tracking information on maps and where the information is updated in real-
time. It is originally targeted for use by radio amateurs in voluntary search
and rescue service in Norway. It consists of a web application and a server 
program (APRS daemon). 
 
This is a _plugin_ that provides database storage using PostgreSQL with the 
PostGIS extension. It is optional and is primarily meant for online 
servers having sufficient memory and storage. It is used on aprs.no. 

### Features

APRS positions (spatiotemporal data) and APRS packets for later analysis.
It is configurable what callsigns are stored and for how long. Queries 
include movement trails, positions covered by digipeaters, etc. 

Client/user-owned data like trackers, static position objects, 
map-extents, map-layer setups, etc through a REST API.

Currently it includes some js snippets that can be installed as 
extensions to the webapp package. Note that the old _webapp_ is to be replaced 
with _webapp2_ and these will eventually go away.

Future uses may include (SAR) mission information, users, etc. 

## System requirements

Linux/Java platform (tested with Debian/Ubuntu/Mint) with
* Java Runtime environment version 8 or later. 
* scala-library version 2.11 or later. You will also need scala-xml
  and scala-parser-combinators packages. 
* polaric-aprsd installed.

## Installation

Feel free to contact me if you plan to use this :) 

Like aprsd, it can be compiled to a Debian package. It needs PostgreSQL and PostGIS to be 
properly installed and configured. See the file _'dbsetup'_ (though it may be a little outdated). 

_'dbinstall'_ is a script that creates the tables and other stuff in the database.. 

