/*!
* Copyright 2002 - 2013 Webdetails, a Pentaho company.  All rights reserved.
* 
* This software was developed by Webdetails and is provided under the terms
* of the Mozilla Public License, Version 2.0, or any later version. You may not use
* this file except in compliance with the license. If you need a copy of the license,
* please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
*
* Software distributed under the Mozilla Public License is distributed on an "AS IS"
* basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
* the license for the specific language governing your rights and limitations.
*/

package pt.webdetails.cpf;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.pentaho.platform.api.engine.IContentGenerator;
import org.pentaho.platform.api.engine.IOutputHandler;
import org.pentaho.platform.api.engine.IParameterProvider;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IPluginManager;
import org.pentaho.platform.api.engine.ObjectFactoryException;
import org.pentaho.platform.engine.core.output.SimpleOutputHandler;
import org.pentaho.platform.engine.core.solution.SimpleParameterProvider;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.web.http.request.HttpRequestParameterProvider;

import pt.webdetails.cpf.plugin.CorePlugin;
import pt.webdetails.cpf.utils.CharsetHelper;
import pt.webdetails.cpf.web.CpfHttpServletResponse;



/**
 * Call to another pentaho plugin through its content generator.
 * Not thread safe.
 */
public class PentahoInterPluginCall extends AbstractInterPluginCall implements Runnable, Callable<String> {

  private ServletResponse response;
  private HttpServletRequest request;
  
  private OutputStream output;
  private IPentahoSession session;
  private IPluginManager pluginManager;


  public PentahoInterPluginCall() {
    
  }

  /**
   * Creates a new call.
   * @param plugin the plugin to call
   * @param method 
   */
  public PentahoInterPluginCall(CorePlugin plugin, String method){    
    super(plugin, method, new HashMap<String, Object>());
  }
  
  public HttpServletRequest getRequest() {
    return request;
  }

  public void setRequest(HttpServletRequest request) {
    this.request = request;
  }

  protected IContentGenerator getContentGenerator() {
    try {
      IContentGenerator contentGenerator = getPluginManager().getContentGenerator( plugin.getName(), getSession() );
      if ( contentGenerator == null ) {
        logger.error( "ContentGenerator for " + plugin.getName() + " could not be fetched." );
      }
      return contentGenerator;
    } catch ( Exception e ) {
      logger.error( "Failed to acquire " + plugin.getName() + " plugin: " + e.toString(), e );
      return null;
    }
  }
  
  /**
   * Put a request parameter 
   * @param name
   * @param value
   * @return this
   */
  public AbstractInterPluginCall putParameter(String name, Object value){
    requestParameters.put(name, value);
    return this;
  }
  
  public void run() {
    IOutputHandler outputHandler = new SimpleOutputHandler(getOutputStream(), false);
    IContentGenerator contentGenerator = getContentGenerator();

    if(contentGenerator != null){
      try {
        contentGenerator.setSession(getSession());
        contentGenerator.setOutputHandler(outputHandler);
        contentGenerator.setParameterProviders(getParameterProviders());
        contentGenerator.createContent();
        
      } catch (Exception e) {
        logger.error("Failed to execute call to plugin: " + e.toString(), e);
      }
    }
    else {
      logger.error("No ContentGenerator.");
    }
    
  }
  
  public String call() {
    setOutputStream(new ByteArrayOutputStream());
    run();
    try{
      return ((ByteArrayOutputStream) getOutputStream()).toString(getEncoding());
    }
    catch(UnsupportedEncodingException uee){
      logger.error("Charset " + getEncoding() + " not supported!!");
      return ((ByteArrayOutputStream) getOutputStream()).toString();
    }
  }

  public void runInPluginClassLoader(){
    getClassLoaderCaller().runInClassLoader(this);
  }

  public String callInPluginClassLoader() {
    try {
      return getClassLoaderCaller().callInClassLoader(this);
    } catch (Exception e) {
      logger.error(e);
      return null;
    }
  }

  public OutputStream getOutputStream(){
    if(output == null){
      output = new ByteArrayOutputStream();
    }
    return output;
  }

  public ServletResponse getResponse() {
    if(response == null){
      logger.debug("No response passed to method " + this.method + ", adding mock response.");
      createDefaultResponse();
    }
    
    return response;
  }

  private void createDefaultResponse() {
    if ( output instanceof ByteArrayOutputStream ) {
      response = new CpfHttpServletResponse( ( ByteArrayOutputStream ) output );
    }
    else {
      response = new CpfHttpServletResponse();
      logger.warn( "OutputStream from response will not be available." );
    }
  }

  public void setResponse(ServletResponse response) {
    this.response = response;
  }
  
  public void setSession(IPentahoSession session) {
    this.session = session;
  }

  public void setOutputStream(OutputStream outputStream){
    this.output = outputStream;
  }
  
  public void setRequestParameters(Map<String, Object> parameters){
    this.requestParameters = parameters;
  }
  
  public void setRequestParameters(IParameterProvider requestParameterProvider){
    if(!requestParameters.isEmpty()) requestParameters.clear();
    
    for(@SuppressWarnings("unchecked")
    Iterator<String> params = requestParameterProvider.getParameterNames(); params.hasNext();){
      String parameterName = params.next();
      requestParameters.put(parameterName, requestParameterProvider.getParameter(parameterName));
    }
  }
  
  protected IPentahoSession getSession(){
    if(session == null){
      session = PentahoSessionHolder.getSession();
    }
    return session;
  }
  
  protected IParameterProvider getRequestParameterProvider(){
    SimpleParameterProvider provider = null;
    if(request != null){
       provider = new HttpRequestParameterProvider(request);
      provider.setParameters(requestParameters);
    } else {
      provider = new SimpleParameterProvider(requestParameters);
    }
    return provider;
  }
  
  protected ClassLoaderAwareCaller getClassLoaderCaller(){
    return new ClassLoaderAwareCaller(getPluginManager().getClassLoader(plugin.getName()));
  }

  protected IPluginManager getPluginManager() {
    if(pluginManager == null){
      pluginManager = PentahoSystem.get(IPluginManager.class, getSession());
    }
    return pluginManager;
  }


  protected IParameterProvider getPathParameterProvider() {
    Map<String, Object> pathMap = new HashMap<String, Object>();
    pathMap.put("path", "/" + method);
    pathMap.put("httpresponse", getResponse());
    if(getRequest() != null){
      pathMap.put("httprequest", getRequest());
    }
    IParameterProvider pathParams = new SimpleParameterProvider(pathMap);
    return pathParams;
  }
  
  protected Map<String,IParameterProvider> getParameterProviders(){
    IParameterProvider requestParams = getRequestParameterProvider();
    IParameterProvider pathParams = getPathParameterProvider();
    Map<String, IParameterProvider> paramProvider = new HashMap<String, IParameterProvider>();
    paramProvider.put(IParameterProvider.SCOPE_REQUEST, requestParams);
    paramProvider.put("path", pathParams);
    return paramProvider;
  }

   protected String getEncoding(){
     return CharsetHelper.getEncoding();
   }

   public boolean pluginExists(){
     try {
       return getPluginManager().getContentGenerator(plugin.getName(), getSession()) != null;
     } catch (ObjectFactoryException e) {
       return false;
     }
 }

}
