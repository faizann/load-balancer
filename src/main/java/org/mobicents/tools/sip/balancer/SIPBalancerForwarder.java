/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.mobicents.tools.sip.balancer;

import gov.nist.javax.sip.header.SIPHeader;

import java.text.ParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.TimeoutEvent;
import javax.sip.Transaction;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.TransactionUnavailableException;
import javax.sip.address.Address;
import javax.sip.address.SipURI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * A transaction stateful UDP Forwarder that listens at a port and forwards to multiple
 * outbound addresses. It keeps a timer thread around that pings the list of
 * proxy servers and sends to the first proxy server.
 * 
 * It uses double record routing to be able to listen on one transport and sends on another transport
 * or allows support for multihoming.
 * 
 * @author M. Ranganathan
 * @author baranowb 
 * @author <A HREF="mailto:jean.deruelle@gmail.com">Jean Deruelle</A>
 */
public class SIPBalancerForwarder implements SipListener {
	private static final Logger logger = Logger.getLogger(SIPBalancerForwarder.class
			.getCanonicalName());

	/*
	 * Those parameters is to indicate to the SIP Load Balancer, from which node comes from the request
	 * so that it can stick the Call Id to this node and correctly route the subsequent requests. 
	 */
	public static final String ROUTE_PARAM_NODE_HOST = "node_host";

	public static final String ROUTE_PARAM_NODE_PORT = "node_port";

	protected static final HashSet<String> dialogCreationMethods=new HashSet<String>(2);
    
    static{
    	dialogCreationMethods.add(Request.INVITE);
    	dialogCreationMethods.add(Request.SUBSCRIBE);
    }      

	public NodeRegister register;
	
    static BalancerContext balancerContext = new BalancerContext();
    
    protected BalancerAlgorithm balancerAlgorithm;
    
    protected String[] extraServerAddresses;
    protected int[] extraServerPorts;
	
	public SIPBalancerForwarder(Properties properties, NodeRegister register) throws IllegalStateException{
		super();
		balancerContext.properties = properties;
		this.register = register;		
	}

	public void start() {

		SipFactory sipFactory = null;
		balancerContext.sipStack = null;

		balancerContext.host = balancerContext.properties.getProperty("host");   
		balancerContext.externalPort = Integer.parseInt(balancerContext.properties.getProperty("externalPort"));
		if(balancerContext.properties.getProperty("internalPort") != null) {
			balancerContext.internalPort = Integer.parseInt(balancerContext.properties.getProperty("internalPort"));
		}
		balancerContext.externalIpLoadBalancerAddress = balancerContext.properties.getProperty("externalIpLoadBalancerAddress");
		balancerContext.internalIpLoadBalancerAddress = balancerContext.properties.getProperty("internalIpLoadBalancerAddress");
		balancerContext.externalTransport = balancerContext.properties.getProperty("externalTransport", "UDP");
		balancerContext.internalTransport = balancerContext.properties.getProperty("internalTransport", "UDP");
		if(balancerContext.properties.getProperty("externalLoadBalancerPort") != null) {
			balancerContext.externalLoadBalancerPort = Integer.parseInt(balancerContext.properties.getProperty("externalLoadBalancerPort"));
		}
		if(balancerContext.properties.getProperty("internalLoadBalancerPort") != null) {
			balancerContext.internalLoadBalancerPort = Integer.parseInt(balancerContext.properties.getProperty("internalLoadBalancerPort"));
		}
		String extraServerNodesString = balancerContext.properties.getProperty("extraServerNodes");
		if(extraServerNodesString != null) {
			extraServerAddresses = extraServerNodesString.split(",");
			extraServerPorts = new int[extraServerAddresses.length];
			for(int q=0; q<extraServerAddresses.length; q++) {
				int indexOfPort = extraServerAddresses[q].indexOf(':');
				if(indexOfPort > 0) {
					extraServerPorts[q] = Integer.parseInt(extraServerAddresses[q].substring(indexOfPort + 1, extraServerAddresses[q].length()));
					extraServerAddresses[q] = extraServerAddresses[q].substring(0, indexOfPort);
					logger.info("Extra Server: " + extraServerAddresses[q] + ":" + extraServerPorts[q]);
				} else {
					extraServerPorts[q] = 5060;
				}
			}
		}
		
        try {
            // Create SipStack object
        	sipFactory = SipFactory.getInstance();
	        sipFactory.setPathName("gov.nist");
	        balancerContext.sipStack = sipFactory.createSipStack(balancerContext.properties);
           
        } catch (PeerUnavailableException pue) {
            // could not find
            // gov.nist.jain.protocol.ip.sip.SipStackImpl
            // in the classpath
            throw new IllegalStateException("Cant create stack due to["+pue.getMessage()+"]", pue);
        }

        try {
        	balancerContext.headerFactory = sipFactory.createHeaderFactory();
        	balancerContext.addressFactory = sipFactory.createAddressFactory();
        	balancerContext.messageFactory = sipFactory.createMessageFactory();

            ListeningPoint externalLp = balancerContext.sipStack.createListeningPoint(balancerContext.host, balancerContext.externalPort, balancerContext.externalTransport);
            balancerContext.externalSipProvider = balancerContext.sipStack.createSipProvider(externalLp);
            balancerContext.externalSipProvider.addSipListener(this);
            
            
            ListeningPoint internalLp = null;
            if(balancerContext.isTwoEntrypoints()) {
            	internalLp = balancerContext.sipStack.createListeningPoint(balancerContext.host, balancerContext.internalPort, balancerContext.internalTransport);
                balancerContext.internalSipProvider = balancerContext.sipStack.createSipProvider(internalLp);
                balancerContext.internalSipProvider.addSipListener(this);
            }


            //Creating the Record Route headers on startup since they can't be changed at runtime and this will avoid the overhead of creating them
            //for each request
            
    		//We need to use double record (better option than record route rewriting) routing otherwise it is impossible :
    		//a) to forward BYE from the callee side to the caller
    		//b) to support different transports		
    		SipURI externalLocalUri = balancerContext.addressFactory
    		        .createSipURI(null, externalLp.getIPAddress());
    		externalLocalUri.setPort(externalLp.getPort());
    		externalLocalUri.setTransportParam(balancerContext.externalTransport);
    		//See RFC 3261 19.1.1 for lr parameter
    		externalLocalUri.setLrParam();
    		Address externalLocalAddress = balancerContext.addressFactory.createAddress(externalLocalUri);
    		externalLocalAddress.setURI(externalLocalUri);
    		if(logger.isLoggable(Level.FINEST)) {
    			logger.finest("adding Record Router Header :"+externalLocalAddress);
    		}                    
    		balancerContext.externalRecordRouteHeader = balancerContext.headerFactory
    		        .createRecordRouteHeader(externalLocalAddress);    
    		
    		if(balancerContext.isTwoEntrypoints()) {
    			SipURI internalLocalUri = balancerContext.addressFactory
    			.createSipURI(null, internalLp.getIPAddress());
    			internalLocalUri.setPort(internalLp.getPort());
    			internalLocalUri.setTransportParam(balancerContext.internalTransport);
    			//See RFC 3261 19.1.1 for lr parameter
    			internalLocalUri.setLrParam();
    			Address internalLocalAddress = balancerContext.addressFactory.createAddress(internalLocalUri);
    			internalLocalAddress.setURI(internalLocalUri);
    			if(logger.isLoggable(Level.FINEST)) {
    				logger.finest("adding Record Router Header :"+internalLocalAddress);
    			}                    
    			balancerContext.internalRecordRouteHeader = balancerContext.headerFactory
    			.createRecordRouteHeader(internalLocalAddress);  
    		}
    		
    		if(balancerContext.externalIpLoadBalancerAddress != null) {
    			SipURI ipLbSipUri = balancerContext.addressFactory
    			.createSipURI(null, balancerContext.externalIpLoadBalancerAddress);
    			ipLbSipUri.setPort(balancerContext.externalLoadBalancerPort);
    			ipLbSipUri.setTransportParam(balancerContext.externalTransport);
    			ipLbSipUri.setLrParam();
    			Address ipLbAdress = balancerContext.addressFactory.createAddress(ipLbSipUri);
    			ipLbAdress.setURI(ipLbSipUri);
    			balancerContext.externalIpBalancerRecordRouteHeader = balancerContext.headerFactory
    			.createRecordRouteHeader(ipLbAdress);
    		}
    		
    		if(balancerContext.internalIpLoadBalancerAddress != null) {
    			SipURI ipLbSipUri = balancerContext.addressFactory
    			.createSipURI(null, balancerContext.internalIpLoadBalancerAddress);
    			ipLbSipUri.setPort(balancerContext.internalLoadBalancerPort);
    			ipLbSipUri.setTransportParam(balancerContext.internalTransport);
    			ipLbSipUri.setLrParam();
    			Address ipLbAdress = balancerContext.addressFactory.createAddress(ipLbSipUri);
    			ipLbAdress.setURI(ipLbSipUri);
    			balancerContext.internalIpBalancerRecordRouteHeader = balancerContext.headerFactory
    			.createRecordRouteHeader(ipLbAdress);
    		}
    		balancerContext.activeExternalHeader = balancerContext.externalIpBalancerRecordRouteHeader != null ?
    				balancerContext.externalIpBalancerRecordRouteHeader : balancerContext.externalRecordRouteHeader;
    		balancerContext.activeInternalHeader = balancerContext.internalIpBalancerRecordRouteHeader != null ?
    				balancerContext.internalIpBalancerRecordRouteHeader : balancerContext.internalRecordRouteHeader;
    		
    		balancerContext.sipStack.start();
        } catch (Exception ex) {
        	throw new IllegalStateException("Can't create sip objects and lps due to["+ex.getMessage()+"]", ex);
        }
        if(logger.isLoggable(Level.INFO)) {
        	logger.info("Sip Balancer started on address " + balancerContext.host + ", external port : " + balancerContext.externalPort + "");
        }              
	}
	
	public void stop() {
		Iterator<SipProvider> sipProviderIterator = balancerContext.sipStack.getSipProviders();
		try{
			while (sipProviderIterator.hasNext()) {
				SipProvider sipProvider = sipProviderIterator.next();
				ListeningPoint[] listeningPoints = sipProvider.getListeningPoints();
				for (ListeningPoint listeningPoint : listeningPoints) {
					if(logger.isLoggable(Level.INFO)) {
						logger.info("Removing the following Listening Point " + listeningPoint);
					}
					sipProvider.removeListeningPoint(listeningPoint);
					balancerContext.sipStack.deleteListeningPoint(listeningPoint);
				}
				if(logger.isLoggable(Level.INFO)) {
					logger.info("Removing the sip provider");
				}
				sipProvider.removeSipListener(this);	
				balancerContext.sipStack.deleteSipProvider(sipProvider);	
				sipProviderIterator = balancerContext.sipStack.getSipProviders();
			}
		} catch (Exception e) {
			throw new IllegalStateException("Cant remove the listening points or sip providers", e);
		}
		
		balancerContext.sipStack.stop();
		balancerContext.sipStack = null;
		if(logger.isLoggable(Level.INFO)) {
			logger.info("Sip Balancer stopped");
		}
	}
	
	public void processDialogTerminated(
			DialogTerminatedEvent dialogTerminatedEvent) {
		// We wont see those
	}

	public void processIOException(IOExceptionEvent exceptionEvent) {
		// Hopefully we wont see those either
	}

	/*
	 * (non-Javadoc)
	 * @see javax.sip.SipListener#processRequest(javax.sip.RequestEvent)
	 */
	public void processRequest(RequestEvent requestEvent) {
		// This will be invoked only by external endpoint
		final SipProvider sipProvider = (SipProvider) requestEvent.getSource();
         
		final Request request = requestEvent.getRequest();
		final String requestMethod = request.getMethod();
		try {	
			updateStats(request);
            forwardRequest(sipProvider,request);          						
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "Unexpected exception while forwarding the request " + request, throwable);
            if(!Request.ACK.equalsIgnoreCase(requestMethod)) {
	            try {
	            	Response response = balancerContext.messageFactory.createResponse(Response.SERVER_INTERNAL_ERROR, request);			
	                sipProvider.sendResponse(response);	
	            } catch (Exception e) {
	            	logger.log(Level.SEVERE, "Unexpected exception while trying to send the error response for this " + request, e);
				}
            }
        }
	}

	private void updateStats(Message message) {
		if(message instanceof Request) {
			balancerContext.requestsProcessed.incrementAndGet();
		} else {
			balancerContext.responsesProcessed.incrementAndGet();
		}		
	}
	
	private SIPNode getNode(String host, int port, String otherTransport) {
		for(SIPNode node : balancerContext.nodes) {
			if(node.getHostName().equals(host) || node.getIp().equals(host)) {
				if(node.getPort() == port) {
					for(String transport : node.getTransports()) {
						if(transport.equalsIgnoreCase(otherTransport)) {
							return node;
						}
					}
				}
			}
		}
		return null;
	}
	
	private boolean isViaHeaderFromServer(Request request) {
		ViaHeader viaHeader = ((ViaHeader)request.getHeader(ViaHeader.NAME));
		String host = viaHeader.getHost();
		String transport = viaHeader.getTransport();
		if(transport == null) transport = "udp";
		int port = viaHeader.getPort();
		if(extraServerAddresses != null) {
			for(int q=0; q<extraServerAddresses.length; q++) {
				if(extraServerAddresses[q].equals(host) && extraServerPorts[q] == port) {
					return true;
				}
			}
		}
		if(getNode(host, port, transport) != null) {
			return true;
		}
		return false;
	}
	
	private boolean isResponseFromServer(Response response) {
		ViaHeader viaHeader = ((ViaHeader)response.getHeader(ViaHeader.NAME));
		String host = viaHeader.getHost();
		String transport = viaHeader.getTransport();
		if(transport == null) transport = "udp";
		int port = viaHeader.getPort();
		if(extraServerAddresses != null) {
			for(int q=0; q<extraServerAddresses.length; q++) {
				if(extraServerAddresses[q].equals(host) && extraServerPorts[q] == port) {
					return false;
				}
			}
		}
		if(getNode(host, port, transport) != null) {
			return false;
		}
		return true;
	}

	/**
	 * @param requestEvent
	 * @param sipProvider
	 * @param originalRequest
	 * @param serverTransaction
	 * @param request
	 * @throws ParseException
	 * @throws InvalidArgumentException
	 * @throws SipException
	 * @throws TransactionUnavailableException
	 */
	private void forwardRequest(
			SipProvider sipProvider,
			Request request)
			throws ParseException, InvalidArgumentException, SipException,
			TransactionUnavailableException {
		if(logger.isLoggable(Level.FINEST)) {
			logger.finest("got request:\n"+request);
		}
		
		boolean isRequestFromServer = false;
		if(!balancerContext.isTwoEntrypoints()) {
			isRequestFromServer = isViaHeaderFromServer(request);
		} else {
			isRequestFromServer = sipProvider.equals(balancerContext.internalSipProvider);
		}
		
		final boolean isCancel = Request.CANCEL.equals(request.getMethod());
		
		if(!isCancel) {
			decreaseMaxForwardsHeader(sipProvider, request);
		}
		
		if(dialogCreationMethods.contains(request.getMethod())) {
			addLBRecordRoute(sipProvider, request);
		}
		
		final String callID = ((CallIdHeader) request.getHeader(CallIdHeader.NAME)).getCallId();
		
		removeRouteHeadersMeantForLB(request);
		
		SIPNode nextNode = null;
		
		if(isRequestFromServer) {
			this.balancerAlgorithm.processInternalRequest(request);
		} else {
			nextNode = this.balancerAlgorithm.processExternalRequest(request);
			if(nextNode == null) {
				throw new RuntimeException("No nodes available");
			}
		}
		
		// Stateless proxies must not use internal state or ransom values when creating branch because they
		// must repeat exactly the same branches for retransmissions
		final ViaHeader via = (ViaHeader) request.getHeader(ViaHeader.NAME);
		String newBranch = via.getBranch() + callID.substring(0, Math.min(callID.length(), 5));
		// Add the via header to the top of the header list.
		final ViaHeader viaHeader = balancerContext.headerFactory.createViaHeader(
				balancerContext.host, balancerContext.externalPort, balancerContext.externalTransport, newBranch);
		request.addHeader(viaHeader); 

		if(logger.isLoggable(Level.FINEST)) {
			logger.finest("ViaHeader added " + viaHeader);
		}		

		if(logger.isLoggable(Level.FINEST)) {
    		logger.finest("Sending the request:\n" + request + "\n on the other side");
    	}
		if(!isRequestFromServer && balancerContext.isTwoEntrypoints()) {
			balancerContext.internalSipProvider.sendRequest(request);
		} else {
			balancerContext.externalSipProvider.sendRequest(request);
		}
	}

	/**
	 * @param sipProvider
	 * @param request
	 * @throws ParseException
	 */
	private void addLBRecordRoute(SipProvider sipProvider, Request request)
	throws ParseException {				
		if(logger.isLoggable(Level.FINEST)) {
			logger.finest("adding Record Router Header :" + balancerContext.activeExternalHeader);
		}
		if(balancerContext.isTwoEntrypoints()) {
			if(sipProvider.equals(balancerContext.externalSipProvider)) {
				if(logger.isLoggable(Level.FINEST)) {
					logger.finest("adding Record Router Header :" + balancerContext.activeExternalHeader);
				}
				request.addHeader(balancerContext.activeExternalHeader);

				if(logger.isLoggable(Level.FINEST)) {
					logger.finest("adding Record Router Header :" + balancerContext.activeInternalHeader);
				}
				request.addHeader(balancerContext.activeInternalHeader);
			} else {
				if(logger.isLoggable(Level.FINEST)) {
					logger.finest("adding Record Router Header :" + balancerContext.activeInternalHeader);
				}
				request.addHeader(balancerContext.activeInternalHeader);

				if(logger.isLoggable(Level.FINEST)) {
					logger.finest("adding Record Router Header :" + balancerContext.activeExternalHeader);
				}
				request.addHeader(balancerContext.activeExternalHeader);			
			}	
		} else {
			request.addHeader(balancerContext.activeExternalHeader);
		}
	}

	/**
	 * This will check if in the route header there is information on which node from the cluster send the request.
	 * If the request is not received from the cluster, this information will not be present. 
	 * @param routeHeader the route header to check
	 * @return the corresponding Sip Node
	 */
	private SIPNode checkRouteHeaderForSipNode(SipURI routeSipUri) {
		SIPNode node = null;
		String hostNode = routeSipUri.getParameter(ROUTE_PARAM_NODE_HOST);
		String hostPort = routeSipUri.getParameter(ROUTE_PARAM_NODE_PORT);
		if(hostNode != null && hostPort != null) {
			int port = Integer.parseInt(hostPort);
			node = register.getNode(hostNode, port, routeSipUri.getTransportParam());
		}
		return node;
	}

	/**
	 * Remove the different route headers that are meant for the Load balancer. 
	 * There is two cases here :
	 * <ul>
	 * <li>* Requests coming from external and going to the cluster : dialog creating requests can have route header so that they go through the LB and subsequent requests 
	 * will have route headers since the LB record routed</li>
	 * <li>* Requests coming from the cluster and going to external : dialog creating requests can have route header so that they go through the LB - those requests will define in the route header
	 * the originating node of the request so that that subsequent requests are routed to the originating node if still alive</li>
	 * </ul>
	 * 
	 * @param request
	 */
	private SIPNode removeRouteHeadersMeantForLB(Request request) {
		if(logger.isLoggable(Level.FINEST)) {
    		logger.finest("Checking if there is any route headers meant for the LB to remove...");
    	}
		SIPNode node = null; 
		//Removing first routeHeader if it is for the sip balancer
		RouteHeader routeHeader = (RouteHeader) request.getHeader(RouteHeader.NAME);
		if(routeHeader != null) {
		    SipURI routeUri = (SipURI)routeHeader.getAddress().getURI();
		    //FIXME check against a list of host we may have too
		    if(!isRouteHeaderExternal(routeUri.getHost(), routeUri.getPort())) {
		    	if(logger.isLoggable(Level.FINEST)) {
		    		logger.finest("this route header is for the LB removing it " + routeUri);
		    	}
		    	request.removeFirst(RouteHeader.NAME);
		    	routeHeader = (RouteHeader) request.getHeader(RouteHeader.NAME);
		    	//since we used double record routing we may have 2 routes corresponding to us here
		        // for ACK and BYE from caller for example
		        node = checkRouteHeaderForSipNode(routeUri);
		        if(routeHeader != null) {
		            routeUri = (SipURI)routeHeader.getAddress().getURI();
		            //FIXME check against a list of host we may have too
		            if(!isRouteHeaderExternal(routeUri.getHost(), routeUri.getPort())) {
		            	if(logger.isLoggable(Level.FINEST)) {
		            		logger.finest("this route header is for the LB removing it " + routeUri);
		            	}
		            	request.removeFirst(RouteHeader.NAME);
		            	if(node == null) {
		            		node = checkRouteHeaderForSipNode(routeUri);
		            	}
		            }
		        }
		    }	                
		}
		if(node !=null) {
			String callId = ((SIPHeader) request.getHeader("Call-ID")).getValue();
			balancerAlgorithm.assignToNode(callId, node);
			if(logger.isLoggable(Level.FINEST)) {
	    		logger.finest("Following node information has been found in one of the route Headers " + node);
	    	}
		}
		return node;
	}
	
	/**
	 * Check if the sip uri is meant for the LB same host and same port
	 * @param sipUri sip Uri to check 
	 * @return
	 */
	private boolean isRouteHeaderExternal(String host, int port) {
		 //FIXME check against a list of host we may have too and add transport
		if(host.equalsIgnoreCase(balancerContext.host) && (port == balancerContext.externalPort || port == balancerContext.internalPort)) {
			return false;
		}
		if((host.equalsIgnoreCase(balancerContext.externalIpLoadBalancerAddress) && port == balancerContext.externalLoadBalancerPort)) {
			return false;
		}
		if((host.equalsIgnoreCase(balancerContext.internalIpLoadBalancerAddress) && port == balancerContext.internalLoadBalancerPort)) {
			return false;
		}
		return true;
	}

	/**
	 * @param sipProvider
	 * @param request
	 * @throws InvalidArgumentException
	 * @throws ParseException
	 * @throws SipException
	 */
	private void decreaseMaxForwardsHeader(SipProvider sipProvider,
			Request request) throws InvalidArgumentException, ParseException,
			SipException {
		// Decreasing the Max Forward Header
		if(logger.isLoggable(Level.FINEST)) {
        	logger.finest("Decreasing  the Max Forward Header ");
        }
		MaxForwardsHeader maxForwardsHeader = (MaxForwardsHeader) request.getHeader(MaxForwardsHeader.NAME);
		if (maxForwardsHeader == null) {
			maxForwardsHeader = balancerContext.headerFactory.createMaxForwardsHeader(70);
			request.addHeader(maxForwardsHeader);
		} else {
			if(maxForwardsHeader.getMaxForwards() - 1 > 0) {
				maxForwardsHeader.setMaxForwards(maxForwardsHeader.getMaxForwards() - 1);
			} else {
				//Max forward header equals to 0, thus sending too many hops response
				Response response = balancerContext.messageFactory.createResponse
		        	(Response.TOO_MANY_HOPS,request);			
		        sipProvider.sendResponse(response);
			}
		}
	}

	/**
	 * @param originalRequest
	 * @param serverTransaction
	 * @throws ParseException
	 * @throws SipException
	 * @throws InvalidArgumentException
	 * @throws TransactionUnavailableException
	 */

	/*
	 * (non-Javadoc)
	 * @see javax.sip.SipListener#processResponse(javax.sip.ResponseEvent)
	 */
	public void processResponse(ResponseEvent responseEvent) {
		SipProvider sipProvider = (SipProvider) responseEvent.getSource();
		Response originalResponse = responseEvent.getResponse();
		if(logger.isLoggable(Level.FINEST)) {
			logger.finest("got response :\n" + originalResponse);
		}

		updateStats(originalResponse);

		final Response response = originalResponse;
		
		// Topmost via header is me. As it is response to external request
		final ViaHeader viaHeader = (ViaHeader) response.getHeader(ViaHeader.NAME);
		
		if(viaHeader!=null && !isRouteHeaderExternal(viaHeader.getHost(), viaHeader.getPort())) {
			response.removeFirst(ViaHeader.NAME);
		}
		
		boolean isResponseFromServer = isResponseFromServer(response);
		if(isResponseFromServer) {
			balancerAlgorithm.processInternalResponse(response);
			try {	
				balancerContext.externalSipProvider.sendResponse(response);
			} catch (Exception ex) {
				logger.log(Level.SEVERE, "Unexpected exception while forwarding the response \n" + response, ex);
			}
		} else {
			balancerAlgorithm.processExternalResponse(response);
			try {	
				balancerContext.internalSipProvider.sendResponse(response);
			} catch (Exception ex) {
				logger.log(Level.SEVERE, "Unexpected exception while forwarding the response \n" + response, ex);
			}
		}



	}

	/*
	 * (non-Javadoc)
	 * @see javax.sip.SipListener#processTimeout(javax.sip.TimeoutEvent)
	 */
	public void processTimeout(TimeoutEvent timeoutEvent) {
		Transaction transaction = null;
		if(timeoutEvent.isServerTransaction()) {
			transaction = timeoutEvent.getServerTransaction();
			if(logger.isLoggable(Level.FINEST)) {
				logger.finest("timeout => " + transaction.getRequest().toString());
			}
		} else {
			transaction = timeoutEvent.getClientTransaction();
			if(logger.isLoggable(Level.FINEST)) {
				logger.finest("timeout => " + transaction.getRequest().toString());
			}
		}
		String callId = ((CallIdHeader)transaction.getRequest().getHeader(CallIdHeader.NAME)).getCallId();
		register.unStickSessionFromNode(callId);
	}

	/*
	 * (non-Javadoc)
	 * @see javax.sip.SipListener#processTransactionTerminated(javax.sip.TransactionTerminatedEvent)
	 */
	public void processTransactionTerminated(
			TransactionTerminatedEvent transactionTerminatedEvent) {
		Transaction transaction = null;
		if(transactionTerminatedEvent.isServerTransaction()) {
			transaction = transactionTerminatedEvent.getServerTransaction();
			if(logger.isLoggable(Level.FINEST)) {
				logger.finest("timeout => " + transaction.getRequest().toString());
			}
		} else {
			transaction = transactionTerminatedEvent.getClientTransaction();
			if(logger.isLoggable(Level.FINEST)) {
				logger.finest("timeout => " + transaction.getRequest().toString());
			}
		}
		if(Request.BYE.equals(transaction.getRequest().getMethod())) {
			String callId = ((CallIdHeader)transaction.getRequest().getHeader(CallIdHeader.NAME)).getCallId();
			register.unStickSessionFromNode(callId);
		}
	}
	
	/**
	 * @return the requestsProcessed
	 */
	public long getNumberOfRequestsProcessed() {
		return balancerContext.requestsProcessed.get();
	}
	
	/**
	 * @return the requestsProcessed
	 */
	public long getNumberOfResponsesProcessed() {
		return balancerContext.responsesProcessed.get();
	}

	public BalancerContext getBalancerAlgorithmContext() {
		return balancerContext;
	}

	public void setBalancerAlgorithmContext(
			BalancerContext balancerAlgorithmContext) {
		this.balancerContext = balancerAlgorithmContext;
	}

	public BalancerAlgorithm getBalancerAlgorithm() {
		return balancerAlgorithm;
	}

	public void setBalancerAlgorithm(BalancerAlgorithm balancerAlgorithm) {
		this.balancerAlgorithm = balancerAlgorithm;
	}
}
