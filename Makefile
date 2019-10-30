##########################################################################
## Change macros below according to your environment and your needs
##
## CLASSDIR if you want to compile to a class directory instead of generating
##          a jar, by using the 'test' target, you may set the directory here.
##
## CLASSPATH Specify where to find the servlet library and the java-cup
##           library. For Debian Linux platform you wont need to change
##           this.
##
## JAVAC: Java compiler
## JAR:   Jar archiver
##########################################################################
  CLASSDIR = classes
      ALIB = aprsd-lib
 CLASSPATH = /usr/share/java/gettext-commons.jar:polaric-aprsd.jar:jcoord-polaric.jar:/usr/share/java/commons-dbcp.jar:/usr/share/java/postgresql-jdbc4.jar:/usr/share/java/postgis-jdbc.jar:$(ALIB)/spark-core-polaric.jar:$(ALIB)/jetty-polaric.jar:/usr/share/java/jackson-annotations.jar
INSTALLDIR = /etc/polaric-aprsd/plugins
     JAVAC = javac -source 11 -target 11
       JAR = jar

# Review (and if necessary) change these if you are going to 
# install by using this makefile

   INSTALL_JAR = $(DESTDIR)/etc/polaric-aprsd/plugins
   INSTALL_WWW = $(DESTDIR)/etc/polaric-webapp/www/auto
   INSTALL_BIN = $(DESTDIR)/usr/bin
INSTALL_CONFIG = $(DESTDIR)/etc/polaric-aprsd
   INSTALL_LOG = $(DESTDIR)/var/log/polaric

  
##################################################
##  things below should not be changed
##
##################################################
    LIBDIR = _lib
 JAVAFLAGS =
 PACKAGES  = core http scala plugin



all: aprs

install: polaric-aprsd.jar
	install -d $(INSTALL_CONFIG)
	install -d $(INSTALL_BIN)
	install -d $(INSTALL_JAR)
	install -d $(INSTALL_WWW)
	install -m 644 js/database.js $(INSTALL_WWW)
	install -m 644 js/00_jquery.simplecolorpicker.js $(INSTALL_WWW)
	install -m 644 js/jquery.simplecolorpicker.css $(INSTALL_WWW)
	install -m 644 js/00_jquery.simplecolorpicker-regularfont.css $(INSTALL_WWW)
	install -m 644 js/01_history.js $(INSTALL_WWW)	
	install -m 644 js/02_heard.js $(INSTALL_WWW)	
	install -m 644 js/edit.png $(INSTALL_WWW)
	install -m 755 -d $(INSTALL_LOG)
	install -m 644 polaric-db.jar $(INSTALL_JAR)


$(INSTALLDIR)/polaric-db.jar: polaric-db.jar
	cp polaric-db.jar $(INSTALLDIR)/polaric-db.jar

	
aprs: $(LIBDIR)
	@make TDIR=$(LIBDIR) CLASSPATH=$(LIBDIR):$(CLASSPATH) compile     
	cd $(LIBDIR);jar cvf ../polaric-db.jar *;cd ..


compile: $(PACKAGES)
	

$(CLASSDIR): 
	mkdir $(CLASSDIR)
	
		
$(LIBDIR):
	mkdir $(LIBDIR)


.PHONY : core
core: 
	$(JAVAC) -d $(TDIR) $(JAVAFLAGS) src/*.java 

	
.PHONY : plugin 
plugin: core http scala
	$(JAVAC) -d $(TDIR) $(JAVAFLAGS) src/plugin/*.java

	
.PHONY : http
http: core
	$(JAVAC) -d $(TDIR) $(JAVAFLAGS) src/http/*.java
	
	
.PHONY : scala
scala: core          
	scalac -d $(TDIR) -classpath $(LIBDIR):$(CLASSPATH) src/http/*.scala


clean:
	@if [ -e ${LIBDIR} ]; then \
		  rm -Rf $(LIBDIR); \
	fi 
	rm -f ./*~ src/*~
