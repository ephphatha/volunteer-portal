<%@ page import="au.org.ala.volunteer.Institution" %>
<!DOCTYPE html>
<html>
	<head>
        <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
        <meta name="layout" content="${grailsApplication.config.ala.skin}"/>
        <g:set var="entityName" value="${message(code: 'institution.label', default: 'Institution')}" />
        <title><g:message code="default.edit.label" args="[entityName]" /></title>
        <g:setProvider library="jquery"/>
	</head>
	<body>
        <cl:headerContent title="${message(code:'default.edit.label', args:[entityName])} - ${institutionInstance?.acronym}">
            <%
                pageScope.crumbs = [
                        [link:createLink(controller:'admin'),label:message(code:'default.admin.label', default:'Admin')],
                        [link:createLink(controller:'institutionAdmin'), label:message(code:'default.institutions.label', default:'Manage Institutions')]
                ]
            %>
        </cl:headerContent>
		<div id="edit-institution" class="content scaffold-edit" role="main">
			<g:hasErrors bean="${institutionInstance}">
			<ul class="errors" role="alert">
				<g:eachError bean="${institutionInstance}" var="error">
				<li <g:if test="${error in org.springframework.validation.FieldError}">data-field-id="${error.field}"</g:if>><g:message error="${error}"/></li>
				</g:eachError>
			</ul>
			</g:hasErrors>
            <div class="container-fluid">
                <div class="row-fluid">
                    <div class="span6">
                        <g:form url="[controller:'institutionAdmin', id:institutionInstance?.id, action:'update']" method="PUT" >
                            <g:hiddenField name="version" value="${institutionInstance?.version}" />
                            <fieldset class="form">
                                <g:render template="form"/>
                            </fieldset>
                            <fieldset class="buttons">
                                <g:actionSubmit class="save" action="update" value="${message(code: 'default.button.update.label', default: 'Update')}" />
                            </fieldset>
                        </g:form>
                    </div>
                    <div class="span1">
                        <button type="button" id="pop-col-val" class="btn btn-primary" name="pop-col-val" data-loading-text="...">
                            <i id="pop-col-icon" class="icon-arrow-populate icon-white"></i>
                        </button>
                    </div>
                    <div class="span5">
                        <h2>Collectory Info</h2>
                        <g:render template="collectory_info"/>
                    </div>
                </div>
                <div class="row-fluid">
                    <div class="span12">
                        <hr />
                        <h2>Images</h2>

                        <table class="table">
                            <tr>
                                <td>
                                    <img src="<cl:institutionImageUrl id="${institutionInstance.id}" />">
                                </td>
                                <td>
                                    <h3>Institution image</h3>
                                    <div class="alert alert-info">
                                        Institution images should be 300 x 150 pixels. They appear on the institution index (or home) page.
                                    </div>
                                    <div>
                                        <button class="btn" type="button" id="btnUploadInstitutionImage">Upload image</button>
                                        <cl:ifInstitutionHasImage institution="${institutionInstance}">
                                            <a href="${createLink(action:'clearImage', id:institutionInstance.id)}" class="btn btn-danger">Clear image</a>
                                        </cl:ifInstitutionHasImage>
                                    </div>
                                </td>
                            </tr>
                            <tr>
                                <td>
                                    <img src="<cl:institutionLogoUrl id="${institutionInstance.id}" />">
                                </td>
                                <td>
                                    <h3>Logo</h3>
                                    <div class="alert alert-info">
                                        Logo images should be 150 x 150 pixels. The logo will appear in the list of institutions, as well as on the institution index (home) page
                                    </div>
                                    <div>
                                        <button class="btn" type="button" id="btnUploadLogoImage">Upload logo</button>
                                        <cl:ifInstitutionHasLogo institution="${institutionInstance}">
                                            <a href="${createLink(action:'clearLogoImage', id:institutionInstance.id)}" class="btn btn-danger">Clear logo</a>
                                        </cl:ifInstitutionHasLogo>
                                    </div>
                                </td>
                            </tr>
                            <tr>
                                <td colspan="2">
                                    <h3>Banner image <small>Optional</small></h3>
                                    <cl:ifInstitutionHasBanner institution="${institutionInstance}">
                                        <img src="<cl:institutionBannerUrl id="${institutionInstance.id}" />" style="margin-bottom: 10px">
                                    </cl:ifInstitutionHasBanner>
                                    <div class="alert alert-info">
                                        Banner images will replace the banner behind the heading at the top of the page. They should be 1170 x 150 pixels in size, and should be light in colour so that the heading text can still be read.
                                        <br/>
                                        For best results try and blend the left and right of your image with HTML color <code>#F0F0E8</code> (<span style="background-color: #F0F0E8">&nbsp;&nbsp;</span>)
                                    </div>
                                    <div>
                                        <button class="btn" type="button" id="btnUploadBannerImage">Upload banner image</button>
                                        <cl:ifInstitutionHasBanner institution="${institutionInstance}">
                                            <a href="${createLink(action:'clearBannerImage', id:institutionInstance.id)}" class="btn btn-danger">Clear banner</a>
                                        </cl:ifInstitutionHasBanner>
                                    </div>
                                </td>
                            </tr>
                        </table>
                    </div>
                </div>
            </div>
		</div>
        <r:script>

            $(document).ready(function() {
                $("#btnUploadBannerImage").click(function(e) {
                    e.preventDefault();
                    bvp.showModal({
                        url: "${createLink(action: "uploadBannerImageFragment", id: institutionInstance.id)}",
                        title: "Upload banner image"
                    });
                });

                $("#btnUploadLogoImage").click(function(e) {
                    e.preventDefault();
                    bvp.showModal({
                        url: "${createLink(action: "uploadLogoImageFragment", id: institutionInstance.id)}",
                        title: "Upload institution logo"
                    });
                });

                $("#btnUploadInstitutionImage").click(function(e) {
                    e.preventDefault();
                    bvp.showModal({
                        url: "${createLink(action: "uploadInstitutionImageFragment", id: institutionInstance.id)}",
                        title: "Upload institution logo"
                    });
                });


            });

        </r:script>
	</body>
</html>
