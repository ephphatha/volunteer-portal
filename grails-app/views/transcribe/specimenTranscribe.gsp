<html>
<%@ page import="au.org.ala.volunteer.Task" %>
<%@ page import="au.org.ala.volunteer.Picklist" %>
<%@ page import="au.org.ala.volunteer.PicklistItem" %>
<%@ page import="au.org.ala.volunteer.TemplateField" %>
<%@ page import="au.org.ala.volunteer.field.*" %>
<%@ page import="au.org.ala.volunteer.FieldCategory" %>
<%@ page import="org.codehaus.groovy.grails.commons.ConfigurationHolder" %>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
<meta name="layout" content="main"/>
<meta name="viewport" content="initial-scale=1.0, user-scalable=no"/>
<title>Transcribe Task ${taskInstance?.id} : ${taskInstance?.project?.name}</title>
<!--  <script type="text/javascript" src="${resource(dir: 'js', file: 'jquery.jqzoom-core-pack.js')}"></script>
  <link rel="stylesheet" href="${resource(dir: 'css', file: 'jquery.jqzoom.css')}"/>-->
<script type="text/javascript" src="${resource(dir: 'js', file: 'mapbox.min.js')}"></script>
<script type="text/javascript" src="${resource(dir: 'js', file: 'jquery.mousewheel.min.js')}"></script>
<script type="text/javascript" src="${resource(dir: 'js/fancybox', file: 'jquery.fancybox-1.3.4.pack.js')}"></script>
<link rel="stylesheet" href="${resource(dir: 'js/fancybox', file: 'jquery.fancybox-1.3.4.css')}"/>
<script type="text/javascript" src="${resource(dir: 'js', file: 'ui.datepicker.js')}"></script>
<link rel="stylesheet" href="${resource(dir: 'css/smoothness', file: 'ui.all.css')}"/>
<script type="text/javascript" src="${resource(dir: 'js', file: 'jquery.validationEngine.js')}"></script>
<script type="text/javascript" src="${resource(dir: 'js', file: 'jquery.validationEngine-en.js')}"></script>
<link rel="stylesheet" href="${resource(dir: 'css', file: 'validationEngine.jquery.css')}"/>
<script type="text/javascript" src="http://maps.google.com/maps/api/js?sensor=false"></script>
<script type="text/javascript">
    var map, marker, circle, locationObj;
    var quotaCount = 0;

    function initialize() {
        geocoder = new google.maps.Geocoder();
        var lat = $('.decimalLatitude').val();
        var lng = $('.decimalLongitude').val();
        var latLng;

        if (lat && lng) {
            latLng = new google.maps.LatLng(lat, lng);
        } else {
            latLng = new google.maps.LatLng(-34.397, 150.644);
        }

        var myOptions = {
            zoom: 10,
            center: latLng,
            scrollwheel: false,
            mapTypeId: google.maps.MapTypeId.ROADMAP
        };

        map = new google.maps.Map(document.getElementById("mapCanvas"), myOptions);

        marker = new google.maps.Marker({
                    position: latLng,
                    //map.getCenter(),
                    title: 'Specimen Location',
                    map: map,
                    draggable: true
                });
        //console.log("adding marker: " + latLng + " (count: " + quotaCount +")");
        // Add a Circle overlay to the map.
        var radius = parseInt($('.coordinatePrecision').val());
        circle = new google.maps.Circle({
                    map: map,
                    radius: radius,
                    // 3000 km
                    strokeWeight: 1,
                    strokeColor: 'white',
                    strokeOpacity: 0.5,
                    fillColor: '#2C48A6',
                    fillOpacity: 0.2
                });
        // bind circle to marker
        circle.bindTo('center', marker, 'position');

        // Add dragging event listeners.
        google.maps.event.addListener(marker, 'dragstart',
                function() {
                    updateMarkerAddress('Dragging...');
                });

        google.maps.event.addListener(marker, 'drag',
                function() {
                    updateMarkerStatus('Dragging...');
                    updateMarkerPosition(marker.getPosition());
                });

        google.maps.event.addListener(marker, 'dragend',
                function() {
                    updateMarkerStatus('Drag ended');
                    geocodePosition(marker.getPosition());
                    map.panTo(marker.getPosition());
                });

        var localityStr = $(':input.verbatimLocality').val();
        if (!$('input#address').val()) {
            $('input#address').val($(':input.verbatimLocality').val());
        }
        if (lat && lng) {
            geocodePosition(latLng);
            updateMarkerPosition(latLng);
        } else if ($('.verbatimLatitude').val() && $('.verbatimLongitude').val()) {
            $('input#address').val($('.verbatimLatitude').val() + "," + $('.verbatimLongitude').val())
            codeAddress();
        } else if (localityStr) {
            codeAddress();
        }
    }

    /**
     * Google geocode function
     */
    function geocodePosition(pos) {
        geocoder.geocode({
                    latLng: pos
                },
                function(responses) {
                    if (responses && responses.length > 0) {
                        //console.log("geocoded position", responses[0]);
                        updateMarkerAddress(responses[0].formatted_address, responses[0]);
                    } else {
                        updateMarkerAddress('Cannot determine address at this location.');
                    }
                });
    }

    /**
     * Reverse geocode coordinates via Google Maps API
     */
    function codeAddress() {
        var address = $('input#address').val();

        if (geocoder && address) {
            //geocoder.getLocations(address, addAddressToPage);
            quotaCount++
            geocoder.geocode({
                        'address': address,
                        region: 'AU'
                    },
                    function(results, status) {
                        if (status == google.maps.GeocoderStatus.OK) {
                            // geocode was successful
                            var latLng = results[0].geometry.location;
                            var lat = latLng.lat();
                            var lon = latLng.lng();
                            var locationStr = results[0].formatted_address;
                            updateMarkerAddress(locationStr, results[0]);
                            updateMarkerPosition(latLng);
                            //initialize();
                            //console.log("moving marker: " + latLng + " (count: " + quotaCount +")");
                            marker.setPosition(latLng);
                            map.panTo(latLng);
                            return true;
                        } else {
                            alert("Geocode was not successful for the following reason: " + status + " (count: " + quotaCount + ")");
                        }
                    });
        }
    }

    function updateMarkerStatus(str) {
        //$(':input.locality').val(str);
    }

    function updateMarkerPosition(latLng) {
        var rnd = 100000000;
        var lat = Math.round(latLng.lat() * rnd) / rnd;
        // round to 8 decimal places
        var lng = Math.round(latLng.lng() * rnd) / rnd;
        // round to 8 decimal places
        //$('.decimalLatitude').val(lat);
        //$('.decimalLongitude').val(lng);
        //$(':input.coordinatePrecision').val(1000);
        $('#infoLat').html(lat);
        $('#infoLng').html(lng);
    }

    function updateMarkerAddress(str, addressObj) {
        //$('#markerAddress').html(str);
        $('#infoLoc').html(str);
        //$('#mapFlashMsg').fadeIn('fast').fadeOut('slow');
        // update form fields with location parts
        if (addressObj && addressObj.address_components) {
            var addressComps = addressObj.address_components;
            locationObj = addressComps; // save to global var
        }
    }

    $(document).ready(function() {
        // Google maps API code
        //initialize();

        // trigger Google geolocation search on search button
        $('#locationSearch').click(function(e) {
            e.preventDefault();
            // ignore the href text - used for data
            codeAddress();
        });

        $('input#address').keypress(function(e) {
            //alert('form key event = ' + e.which);
            if (e.which == 13) {
                codeAddress();
            }
        });

        $('.coordinatePrecision, #infoUncert').change(function(e) {
            var rad = parseInt($(this).val());
            circle.setRadius(rad);
            //updateTitleAttr(rad);
        })

        $("input.scientificName").autocomplete('http://bie.ala.org.au/search/auto.jsonp', {
                    extraParams: {
                        limit: 100
                    },
                    dataType: 'jsonp',
                    parse: function(data) {
                        var rows = new Array();
                        data = data.autoCompleteList;
                        for (var i = 0; i < data.length; i++) {
                            rows[i] = {
                                data: data[i],
                                value: data[i].matchedNames[0],
                                result: data[i].matchedNames[0]
                            };
                        }
                        return rows;
                    },
                    matchSubset: true,
                    formatItem: function(row, i, n) {
                        return row.matchedNames[0];
                    },
                    cacheLength: 10,
                    minChars: 3,
                    scroll: false,
                    max: 10,
                    selectFirst: false
                }).result(function(event, item) {
                    // user has selected an autocomplete item
                    $(':input.taxonConceptID').val(item.guid);
                });

        $("input.recordedBy").autocomplete("${createLink(action:'autocomplete', controller:'picklistItem')}", {
                    extraParams: {
                        picklist: "recordedBy"
                    },
                    dataType: 'json',
                    parse: function(data) {
                        var rows = new Array();
                        data = data.autoCompleteList;
                        for (var i = 0; i < data.length; i++) {
                            rows[i] = {
                                data: data[i],
                                value: data[i].name,
                                result: data[i].name
                            };
                        }
                        return rows;
                    },
                    matchSubset: true,
                    formatItem: function(row, i, n) {
                        return row.name;
                    },
                    cacheLength: 10,
                    minChars: 1,
                    scroll: false,
                    max: 10,
                    selectFirst: false
                }).result(function(event, item) {
                    // user has selected an autocomplete item
                    $(':input.recordedByID').val(item.key);
                });

        // MapBox for image zooming & panning
        $('#viewport').mapbox({
                    'zoom': true, // does map zoom?
                    'pan': true,
                    'doubleClickZoom': true,
                    'layerSplit': 2,
                    'mousewheel': true
                });
        $(".map-control a").click(function() {//control panel
            var viewport = $("#viewport");
            //this.className is same as method to be called
            if (this.className == "zoom" || this.className == "back") {
                viewport.mapbox(this.className, 2);//step twice
            }
            else {
                viewport.mapbox(this.className);
            }
            return false;
        });

        // prevent enter key submitting form (for geocode search mainly)
        $(".transcribeForm").keypress(function(e) {
            //alert('form key event = ' + e.which);
            if (e.which == 13) {
                var $targ = $(e.target);

                if (!$targ.is("textarea") && !$targ.is(":button,:submit")) {
                    var focusNext = false;
                    $(this).find(":input:visible:not([disabled],[readonly]), a").each(function() {
                        if (this === e.target) {
                            focusNext = true;
                        }
                        else if (focusNext) {
                            $(this).focus();
                            return false;
                        }
                    });

                    return false;
                }
            }
        });

        // show map popup
        var opts = {
            titleShow: false,
            onComplete: initialize,
            autoDimensions: false,
            width: 650,
            height: 420
        }
        $('button#geolocate').fancybox(opts);

        // catch the clear button
        $('button#clearLocation').click(function() {
            $('form.transcribeForm').validate();
            $('form.transcribeForm').submit();
        });

        // catch button on map
        $('#setLocationFields').click(function(e) {
            e.preventDefault();
            // copy map fields into main form
            $('.decimalLatitude').val($('#infoLat').html());
            $('.decimalLongitude').val($('#infoLng').html());
            $(':input.coordinatePrecision').val($('#infoUncert').val());
            // locationObj is a global var set from geocoding lookup
            for (var i = 0; i < locationObj.length; i++) {
                var name = locationObj[i].long_name;
                var type = locationObj[i].types[0];
                var hasLocality = false;
                //console.log(i+". type: "+type+" = "+name);
                // go through each avail option
                if (type == 'country') {
                    //$(':input.countryCode').val(name1);
                    $(':input.country').val(name);
                } else if (type == 'locality') {
                    $(':input.locality').val(name);
                    hasLocality = true;
                } else if (type == 'administrative_area_level_1') {
                    $(':input.stateProvince').val(name);
                } else {
                    //$(':input.locality').val(name);
                }
            }

            $.fancybox.close(); // close the popup
        });

        // form validation
        //$("form.transcribeForm").validationEngine();

        $(":input.save").click(function(e) {
            //e.preventDefault();
            // TODO: Fix this - not working anymore?
            if (!$("form.transcribeForm").validationEngine({returnIsValid:true})) {
                //alert("Validation failed.");
                e.preventDefault();
                $("form.transcribeForm").validationEngine();
                //console.log("Validation failed");
            }
        });

        // Date Picker
        $(":input.datePicker").datepicker({
                    buttonImage: "${resource(dir: 'images', file: 'calendar.png')}",
                    yearRange: '-200:+00',
                    dateFormat: 'yy-mm-dd'}
        ).css('width', '185px').after(' (YYYY-MM-DD)');

    }); // end document ready

</script>
</head>

<body class="two-column-right">
<div class="nav">
    <span class="menuButton"><a class="crumb" href="${createLink(uri: '/')}"><g:message code="default.home.label"/></a>
    </span>
    &gt;
    <span class="menuButton"><a class="crumb" href="${createLink(action: 'list', controller: 'task')}"><g:message
            code="default.task.label" default="Tasks"/></a></span>
    &gt;
    <span class="menuButton">${(validator) ? 'Validate' : 'Transcribe'} Task ${taskInstance?.id}</span>
</div>

<div class="body">
    <h1>${(validator) ? 'Validate' : 'Transcribe'} Task: ${taskInstance?.project?.name} (ID: ${taskInstance?.id})</h1>

    <g:if test="${taskInstance}">
        <g:form controller="${validator ? "transcribe" : "validate"}" class="transcribeForm">
            <g:hiddenField name="recordId" value="${taskInstance?.id}"/>
            <div class="dialog" style="clear: both">
                <g:each in="${taskInstance.multimedia}" var="m">
                    <g:set var="imageUrl" value="${ConfigurationHolder.config.server.url}${m.filePath}"/>
                    <div class="imageWrapper">
                        <div id="viewport">
                            <div style="background: url(${imageUrl.replaceFirst(/\.([a-zA-Z]*)$/, '_small.$1')}) no-repeat; width: 600px; height: 400px;">
                                <!--top level map content goes here-->
                            </div>

                            <div style="height: 1280px; width: 1920px;">
                                <img src="${imageUrl.replaceFirst(/\.([a-zA-Z]*)$/, '_medium.$1')}" alt=""/>

                                <div class="mapcontent"><!--map content goes here--></div>
                            </div>

                            <div style="height: 2000px; width: 3000px;">
                                <img src="${imageUrl.replaceFirst(/\.([a-zA-Z]*)$/, '_large.$1')}" alt=""/>

                                <div class="mapcontent"><!--map content goes here--></div>
                            </div>

                            <div style="height: 3168px; width: 4752px;">
                                <img src="${imageUrl}" alt=""/>

                                <div class="mapcontent"><!--map content goes here--></div>
                            </div>
                        </div>

                        <div class="map-control">
                            <a href="#left" class="left">Left</a>
                            <a href="#right" class="right">Right</a>
                            <a href="#up" class="up">Up</a>
                            <a href="#down" class="down">Down</a>
                            <a href="#zoom" class="zoom">Zoom</a>
                            <a href="#zoom_out" class="back">Back</a>
                        </div>
                    </div>

                </g:each>
                <div id="taskMetadata">
                    <div id="institutionLogo"></div>
                    <h3>Specimen Metadata</h3>
                    <ul id="taskMetadata">
                        <li><div>Institution:</div> <span id="institutionCode">${recordValues?.get(0)?.institutionCode}</span></li>
                        <li><div>Project:</div> ${taskInstance?.project?.name}</li>
                        <li><div>Catalogue No.:</div> ${recordValues?.get(0)?.catalogNumber}</li>
                        <li><div>Taxa:</div> ${recordValues?.get(0)?.scientificName}</li>
                    </ul>
                </div>
                <div style="clear:both;"></div>

                <div id="transcribeFields">
                    <table style="width: 100%">
                        <thead/>
                        <tbody>
                        <g:each in="${TemplateField.findAllByFieldType('occurrenceRemarks')}" var="field">
                            <g:fieldFromTemplateField templateField="${field}" recordValues="${recordValues}"/>
                        </g:each>
                        </tbody>
                    </table>
                    <table style="width: 100%">
                        <thead>
                        <tr><th><h3>Collection Event</h3></th></tr>
                        </thead>
                        <tbody>
                        <g:each in="${TemplateField.findAllByCategory(FieldCategory.collectionEvent, [sort:'id'])}"
                                var="field">
                            <g:fieldFromTemplateField templateField="${field}" recordValues="${recordValues}"/>
                        </g:each>
                        </tbody>
                    </table>

                    <table style="width: 100%">
                        <thead>
                        <tr>
                            <th><h3>Location</h3></th>
                            <th><button id="geolocate" href="#mapWidgets"
                                        title="Show geolocate tools popup">Show mapping tool</button></th>
                        </tr>
                        </thead>
                        <tbody>
                        <g:each in="${TemplateField.findAllByCategory(FieldCategory.location, [sort:'id'])}"
                                var="field">
                            <g:fieldFromTemplateField templateField="${field}" recordValues="${recordValues}"/>
                        </g:each>
                        </tbody>
                    </table>

                    <div style="display:none">
                        <div id="mapWidgets">
                            <div id="mapWrapper">
                                <div id="mapCanvas"></div>
                                <div id="mapHelp">Hint: you can drag & drop the marker icon to set the location data</div>
                            </div>

                            <div id="mapInfo">
                                <div id="sightingAddress">
                                    <h4>Locality Search</h4>
                                    %{--<label for="address">Locality/Coodinates: </label>--}%
                                    <input name="address" id="address" size="32" value=""/>
                                    <input id="locationSearch" type="button" value="Search"/>

                                    <div id="searchHint">Search for a locality, place of interest, address, postcode or GPS coordinates (as lat, long)</div>
                                </div>

                                <h4>Location Data</h4>

                                <div>Latitude: <span id="infoLat"></span></div>

                                <div>Longitude: <span id="infoLng"></span></div>

                                <div>Location: <span id="infoLoc"></span></div>

                                <div>Coordinate Uncertainty: <select id="infoUncert">
                                    <option>10</option>
                                    <option>50</option>
                                    <option>100</option>
                                    <option>500</option>
                                    <option selected="selected">1000</option>
                                    <option>10000</option>
                                </select></div>

                                <div style="text-align: center; padding: 10px;">
                                    <input id="setLocationFields" type="button" value="Copy values to main form"/>
                                </div>
                            </div>
                        </div>
                    </div>
                    <table style="width: 100%">
                        <thead>
                        <tr><th><h3>Identification</h3></th></tr>
                        </thead>
                        <tbody>
                        <g:each in="${TemplateField.findAllByCategory(FieldCategory.identification, [sort:'id'])}"
                                var="field">
                            <g:fieldFromTemplateField templateField="${field}" recordValues="${recordValues}"/>
                        </g:each>
                        </tbody>
                    </table>
                </div>
            </div>

            <div class="buttons" style="clear: both">
                <g:hiddenField name="id" value="${taskInstance?.id}"/>
                <g:if test="${validator}">
                    <span class="button"><g:actionSubmit class="validate" action="validate"
                                                         value="${message(code: 'default.button.validate.label', default: 'Validate')}"/></span>
                    <span class="button"><g:actionSubmit class="dontValidate" action="dontValidate"
                                                         value="${message(code: 'default.button.dont.validate.label', default: 'Dont validate')}"/></span>
                </g:if>
                <g:else>
                    <span class="button"><g:actionSubmit class="save" action="save"
                                                         value="${message(code: 'default.button.save.label', default: 'Save')}"/></span>
                    <span class="button"><g:actionSubmit class="savePartial" action="savePartial"
                                                         value="${message(code: 'default.button.save.partial.label', default: 'Save partially complete')}"/></span>
                    <span class="button"><g:actionSubmit class="skip" action="showNextFromAny"
                                                         value="${message(code: 'default.button.skip.label', default: 'Skip')}"/></span>
                </g:else>
            </div>
        </g:form>
    </g:if>
    <g:else>
        No tasks loaded for this project !
    </g:else>
</div>
</body>
</html>