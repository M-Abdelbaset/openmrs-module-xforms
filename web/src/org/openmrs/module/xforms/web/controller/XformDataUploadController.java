package org.openmrs.module.xforms.web.controller;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.kxml2.kdom.Document;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.module.xforms.XformBuilder;
import org.openmrs.module.xforms.XformConstants;
import org.openmrs.module.xforms.XformObsEdit;
import org.openmrs.module.xforms.XformPatientEdit;
import org.openmrs.module.xforms.XformsServer;
import org.openmrs.module.xforms.download.XformDataUploadManager;
import org.openmrs.module.xforms.util.XformsUtil;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.SimpleFormController;

import com.jcraft.jzlib.JZlib;
import com.jcraft.jzlib.ZOutputStream;


//TODO This class is to be deleted as it functionality is now done by XformDataUploadServlet

/**
 * Provides XForm data upload services.
 * 
 * @author Daniel
 *
 */
public class XformDataUploadController extends SimpleFormController{

	/** Logger for this class and subclasses */
	protected final Log log = LogFactory.getLog(getClass());

	@Override
	protected Map referenceData(HttpServletRequest request, Object obj, Errors err) throws Exception {
		return new HashMap<String,Object>();
	}


	@Override
	protected ModelAndView onSubmit(HttpServletRequest request, HttpServletResponse response, Object object, BindException exceptions) throws Exception {						

		byte status = XformsServer.STATUS_NULL;

		//try to authenticate users who logon inline (with the request).
		XformsUtil.authenticateInlineUser(request);

		// check if user is authenticated
		if (XformsUtil.isAuthenticated(request,response,"/module/xforms/xformDataUpload.form")){
			response.setCharacterEncoding(XformConstants.DEFAULT_CHARACTER_ENCODING);

			//check if external client sending multiple filled forms.
			if(XformConstants.TRUE_TEXT_VALUE.equalsIgnoreCase(request.getParameter(XformConstants.REQUEST_PARAM_BATCH_ENTRY))){            
				try{
					String serializerKey = request.getParameter("serializer");
					if(serializerKey == null || serializerKey.trim().length() == 0)
						serializerKey = XformConstants.GLOBAL_PROP_KEY_XFORM_SERIALIZER;

					XformDataUploadManager.submitXforms(request.getInputStream(),request.getSession().getId(),serializerKey);
					status = XformsServer.STATUS_SUCCESS;
				}
				catch(Exception e){
					log.error( e.getMessage(),e);
					status = XformsServer.STATUS_FAILURE; 
				}
			}
			else{ //else single form filled from browser.
				String xml = IOUtils.toString(request.getInputStream());
				if("edit".equals(request.getParameter("mode")))
					processXformEdit(request,xml);
				else
					XformDataUploadManager.processXform(xml,request.getSession().getId(),XformsUtil.getEnterer());

				setSingleEntryResponse(request, response);
			}
		}

		if(status != XformsServer.STATUS_NULL){
			//GZIPOutputStream gzip = new GZIPOutputStream(response.getOutputStream());
			ZOutputStream gzip = new ZOutputStream(response.getOutputStream(),JZlib.Z_BEST_COMPRESSION);
			DataOutputStream dos = new DataOutputStream(gzip);
			dos.writeByte(status);
			dos.flush();
			gzip.finish();
		}

		return null;
	}

	private void processXformEdit(HttpServletRequest request,String xml) throws Exception{
		Document doc = XformBuilder.getDocument(xml);
		
		if(XformPatientEdit.isPatientElement(doc.getRootElement())){
			Patient patient = XformPatientEdit.getEditedPatient(doc.getRootElement());			
			Context.getPatientService().savePatient(patient);
		}
		else{
			Set<Obs> obs2Void = new HashSet<Obs>();
			Encounter encounter = XformObsEdit.getEditedEncounter(request,doc.getRootElement(),obs2Void);

			//TODO These two below need to be put in a transaction
			Context.getEncounterService().saveEncounter(encounter);

			ObsService obsService = Context.getObsService();
			for(Obs obs : obs2Void)
				obsService.voidObs(obs, "xformsmodule");
		}
	}


	/**
	 * Write the response after processing an xform submitted from the browser.
	 * 
	 * @param request - the request.
	 * @param response - the response.
	 */
	private void setSingleEntryResponse(HttpServletRequest request, HttpServletResponse response){

		String searchNew = Context.getAdministrationService().getGlobalProperty("xforms.searchNewPatientAfterFormSubmission");
		String url = "/findPatient.htm";
		if(XformConstants.FALSE_TEXT_VALUE.equalsIgnoreCase(searchNew)){
			String patientId = request.getParameter(XformConstants.REQUEST_PARAM_PATIENT_ID);
			url = "/patientDashboard.form?patientId="+patientId;
		}
		url = request.getContextPath() + url;

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html;charset=utf-8");

		try{
			//We are using an iframe to display the xform within the page.
			//So this response just tells the iframe parent document to go to either the
			//patient dashboard, or to the search patient screen, depending on the user's settings.
			response.getOutputStream().println("<html>" + "<head>"
					+"<script type='text/javascript'> window.onload=function() {self.parent.location.href='" + url + "';}; </script>"
					+"</head>" + "</html>");
		}
		catch(IOException e){
			log.error(e.getMessage(),e);
		}
	}

	@Override
	protected Object formBackingObject(HttpServletRequest request) throws Exception { 
		return "";
	}
}
