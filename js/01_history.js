 
 
 
 /********************************************************************************************
  *  Trails (history)
  ********************************************************************************************/
 
 var hist = null;
 
 function searchHistData(call) {
    hist = new histData(call); 
 }
 
 
 
 function histData(call)
 {
   var t = this; 
   t.call = ""; 
   t.fromdate = null; 
   t.fromtime = null; 
   t.todate = null; 
   t.totime = null; 
   t.hist = []; 
   

   if (storage['polaric.db.hist'] != null)
     t.hist = JSON.parse(storage['polaric.db.hist']);
   
   if (ses_storage['polaric.db.hist.call'] != null)
     t.fromdate = ses_storage['polaric.db.hist.call'];
   
   if (ses_storage['polaric.db.hist.from'] != null)
     t.fromdate = JSON.parse(ses_storage['polaric.db.hist.from']);
   
   if (ses_storage['polaric.db.hist.tfrom'] != null)
     t.fromtime = JSON.parse(ses_storage['polaric.db.hist.tfrom']);
   
   if (ses_storage['polaric.db.hist.to'] != null)
     t.todate = JSON.parse(ses_storage['polaric.db.hist.to']);
   
   if (ses_storage['polaric.db.hist.tto'] != null)
     t.totime = JSON.parse(ses_storage['polaric.db.hist.tto']);
   
   
   if (t.fromdate == null)
     t.fromdate = formatDate(new Date());
   if (t.todate == null || t.todate == '-')
     t.todate = formatDate(new Date());
   if (t.fromtime == null)
     t.fromtime = formatTime(new Date());
   if (t.totime == null || t.totime == '-')
     t.totime = formatTime(new Date());   
   if (call != null)
     t.call = call;
   
   
   var x = popupwindow(document.getElementById("anchor"),
        ' <h1>Generere historisk spor</h1><hr><form>' +
        ' <span class="sleftlab">Stasjon: </span><input type="text"  size="10" id="findcall" value="'
            + t.call + '"/><br> '+
        ' <span class="sleftlab">Tid start: </span><input type="text"  size="10" id="tfrom" value="'
           + t.fromdate + 
        '"/>&nbsp;<input type="text"  size="4" id="tfromt" value="'
           + t.fromtime + '"/> <br> '+
        ' <span class="sleftlab">Tid slutt: </span><input type="text" size="10" id="tto" value="'
           + t.todate + 
        '"/>&nbsp;<input type="text" size="4" id="ttot" value="'
           + t.totime + '"/>&nbsp; <input type="checkbox" id="ttoopen"> Åpen slutt <br> '+
        '<hr>'+
        '<div id="searchlist"></div>' +
        '<hr>'+
        ' <input id="searchbutton" type="button"' +
        ' value="Søk" />'+ 
        
        ' <input id="addbutton" title="Legg spor til liste" type="button"' +
        ' value="Legg til" />'+
        
        ' <input id="showallbutton" title="Vis alle spor i liste" type="button"' +
        ' value="Vis alle" />'+
        
        ' <input id="exportbutton" title="Eksporter spor (i liste) til GPX format" type="button"' +
        ' value="Eksport" />'+
        
        ' <input id="clearbutton" title="Nullstill liste" type="button"' +
        ' value="Nullstill" />'+
        
        '</form><br>' +
        '<iframe id="downloadframe" style="display:none"></iframe>', 50, 70, null) 
   
   t.displayList(); 
   
   $('#ttoopen').click( function() {
     if ($('#ttoopen').attr('checked'))
       $('#tto,#ttot').prop('disabled',true);
     else 
       $('#tto,#ttot').removeProp('disabled');
   });
   
   $('#searchbutton').click( function() {
     t.getItem();
     t.getHistXmlData( t.call.toUpperCase(), t.fromdate+"/"+t.fromtime, t.todate+"/"+t.totime ); 
     t.saveList();
   });
   
   $('#addbutton').click( function() {
     t.getItem();
     t.call = t.call.toUpperCase();
     t.hist.push({ call:t.call, fromdate:t.fromdate, todate:t.todate, 
       fromtime: t.fromtime, totime: t.totime});
     t.displayList(); 
     t.saveList();
   });
   
   $('#showallbutton').click( function() {
     t.showAll();
   });
   
   $('#exportbutton').click( function() {
     t.exportGpx();
   });
   
   $('#clearbutton').click( function() {
     t.hist = [];
     t.displayList(); 
     t.saveList();
   });
   
   $('#tfrom,#tto').datepicker({ dateFormat: 'yy-mm-dd' });
   $(x).resizable(); 
 
}
 
 
 
 histData.prototype.saveList = function() {
   storage['polaric.db.hist'] = JSON.stringify(this.hist);
   ses_storage['polaric.db.hist.call'] = this.call;
   ses_storage['polaric.db.hist.from'] = JSON.stringify(this.fromdate);
   ses_storage['polaric.db.hist.tfrom'] = JSON.stringify(this.fromtime);
   ses_storage['polaric.db.hist.to'] = JSON.stringify(this.todate);
   ses_storage['polaric.db.hist.tto'] = JSON.stringify(this.totime);
 }
 
 
 
histData.prototype.displayList = function()
{
   var txt = "";
   var t = this; 
   for (i=0; i<this.hist.length; i++) {
      txt += '<img title="remove" src="images/edit-delete.png" height="14" id="deleteItem'+i+'"> '+
             '<img title="edit" src="images/edit.png" height="14" id="editItem'+i+'"> '+
         this.hist[i].call + ' : ' + this.hist[i].fromdate+" "+ this.hist[i].fromtime + ' - ' +
         this.hist[i].todate + ' ' + this.hist[i].totime + '<br>';
   }
   document.getElementById("searchlist").innerHTML = txt; 
   
   for (i=0; i<this.hist.length; i++)
   { 
      $('#deleteItem'+i).click(handleDelete(i)) ;
      $('#editItem'+i).click(handleEdit(i)) ;
   }

   function handleDelete(i) { return function() { t.deleteItem(i); }}
   function handleEdit(i) { return function() { t.editItem(i); }}
}


 
 histData.prototype.getItem = function()
 {
    var t = this;
    t.call     = $('#findcall').val();
    t.fromdate = $('#tfrom').val();
    t.fromtime = $('#tfromt').val();
    if ($('#ttoopen').is(':checked')) 
       t.todate = t.totime = '-';
    else
    {
       t.todate   = $('#tto').val();    
       t.totime   = $('#ttot').val();
    }
 }

 

 
 
 Array.prototype.remove = function(from, to) {
   var rest = this.slice((to || from) + 1 || this.length);
   this.length = from < 0 ? this.length + from : from;
   return this.push.apply(this, rest);
 };
 
 
 
 
 histData.prototype.deleteItem = function(idx)
 {
    this.hist.remove(idx);
    this.displayList();
    this.saveList();
 }
 
 
 
 histData.prototype.editItem = function(idx)
 {
    var t = this;
    $('#findcall').val(t.hist[idx].call);
    $('#tfrom').val(t.hist[idx].fromdate);
    $('#tfromt').val(t.hist[idx].fromtime);
    if (t.hist[idx].todate == '-') {
       $('#ttoopen').attr('checked', true);
       $('#tto,#ttot').prop('disabled',true);
       $('#tto').val(formatDate(new Date()));
       $('#ttot').val(formatTime(new Date()));
    }
    else {
       $('#ttoopen').attr('checked', false);
       $('#tto,#ttot').removeProp('disabled');
       $('#tto').val(t.hist[idx].todate);
       $('#ttot').val(t.hist[idx].totime);
    }
    this.getItem(); 
    this.deleteItem(idx);
    this.saveList();
 }
 
 
 
 histData.prototype.extentQuery = function()
 {
    var ext = myKaMap.getGeoExtents();
    var flt = "";
    if (filterProfiles.selectedProf() != null)
       flt = "&filter="+filterProfiles.selectedProf();
      return "x1="  + roundDeg(ext[0]) + "&x2="+ roundDeg(ext[1]) +
             "&x3=" + roundDeg(ext[2]) + "&x4="+ roundDeg(ext[3]) + flt ;
 }
 
 
 
 histData.prototype.showAll = function()
 {
    abortCall(lastXmlCall);
    mapupdate_suspend(120*1000);
    if (myOverlay != null) 
       myOverlay.removePoint(); 
 
    for (i=0; i < this.hist.length; i++)  
       myOverlay.loadXml('srv/htrail?'+this.extentQuery() + '&scale='+currentScale+
        '&station=' + this.hist[i].call + '&tfrom='+ this.hist[i].fromdate+"/"+this.hist[i].fromtime + 
        '&tto='+  this.hist[i].todate+"/"+this.hist[i].totime + (clientses!=null? '&clientses='+clientses : ""));
 }
 
 
 
 histData.prototype.getHistXmlData = function(stn, tfrom, tto)
 { 
    abortCall(lastXmlCall);
    mapupdate_suspend(120*1000);
    if (myOverlay != null) 
       myOverlay.removePoint(); 
       myOverlay.loadXml('srv/htrail?'+this.extentQuery() + '&scale='+currentScale+
        '&station=' + stn + '&tfrom='+ tfrom + '&tto='+tto+ (clientses!=null? '&clientses='+clientses : ""));
 }
 
 
 
 histData.prototype.exportGpx = function()
 {
    parms = 'ntracks=' + this.hist.length;
    for (i=0; i < this.hist.length; i++)
      parms += '&station' + i + '=' + this.hist[i].call + '&tfrom' + i + '=' + this.hist[i].fromdate+"/" + 
         this.hist[i].fromtime + '&tto' + i + '=' +  this.hist[i].todate + "/" + this.hist[i].totime;
 
    document.getElementById("downloadframe").src ='srv/gpx?' + parms; 
 }
 
 
 
 function formatDate(d)
 {
    return ""+d.getFullYear() + "-" + 
      (d.getMonth()<9 ? "0" : "") + (d.getMonth()+1) + "-" +
      (d.getDate()<10 ? "0" : "")  + d.getDate();
 }
 
 
 
 
 function formatTime(d)
 {
    return "" +
      (d.getHours()<10 ? "0" : "") + d.getHours() + ":" +
      (d.getMinutes()<10 ? "0" : "") + d.getMinutes();
 }
 
 