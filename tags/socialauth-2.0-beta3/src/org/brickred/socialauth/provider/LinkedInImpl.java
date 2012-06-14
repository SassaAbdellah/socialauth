/*
 ===========================================================================
 Copyright (c) 2010 BrickRed Technologies Limited

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sub-license, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 ===========================================================================

 */

package org.brickred.socialauth.provider;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.brickred.socialauth.AbstractProvider;
import org.brickred.socialauth.AuthProvider;
import org.brickred.socialauth.Contact;
import org.brickred.socialauth.Permission;
import org.brickred.socialauth.Profile;
import org.brickred.socialauth.exception.ProviderStateException;
import org.brickred.socialauth.exception.ServerDataException;
import org.brickred.socialauth.exception.SocialAuthException;
import org.brickred.socialauth.util.AccessGrant;
import org.brickred.socialauth.util.BirthDate;
import org.brickred.socialauth.util.Constants;
import org.brickred.socialauth.util.MethodType;
import org.brickred.socialauth.util.OAuthConfig;
import org.brickred.socialauth.util.OAuthConsumer;
import org.brickred.socialauth.util.Response;
import org.brickred.socialauth.util.SocialAuthUtil;
import org.brickred.socialauth.util.XMLParseUtil;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Implementation of Linkedin provider. This uses the oAuth API provided by
 * Linkedin
 * 
 * 
 * @author tarunn@brickred.com
 * 
 */

public class LinkedInImpl extends AbstractProvider implements AuthProvider,
		Serializable {

	private static final long serialVersionUID = -6141448721085510813L;
	private static final String REQUEST_TOKEN_URL = "https://api.linkedin.com/uas/oauth/requestToken";
	private static final String AUTHORIZATION_URL = "https://api.linkedin.com/uas/oauth/authenticate";
	private static final String ACCESS_TOKEN_URL = "https://api.linkedin.com/uas/oauth/accessToken";
	private static final String CONNECTION_URL = "http://api.linkedin.com/v1/people/~/connections:(id,first-name,last-name,public-profile-url)";
	private static final String UPDATE_STATUS_URL = "http://api.linkedin.com/v1/people/~/shares";
	private static final String PROFILE_URL = "http://api.linkedin.com/v1/people/~:(id,first-name,last-name,languages,date-of-birth,picture-url,location:(name))";
	private static final String STATUS_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><share><comment>%1$s</comment><visibility><code>anyone</code></visibility></share>";
	private final Log LOG = LogFactory.getLog(LinkedInImpl.class);

	private Permission scope;
	private boolean isVerify;
	private AccessGrant requestToken;
	private AccessGrant accessToken;
	private OAuthConsumer oauth;
	private OAuthConfig config;
	private Profile userProfile;

	/**
	 * Stores configuration for the provider
	 * 
	 * @param providerConfig
	 *            It contains the configuration of application like consumer key
	 *            and consumer secret
	 * @throws Exception
	 */
	public LinkedInImpl(final OAuthConfig providerConfig) throws Exception {
		config = providerConfig;
		oauth = new OAuthConsumer(config);
	}

	/**
	 * Stores access grant for the provider
	 * 
	 * @param accessGrant
	 *            It contains the access token and other information
	 * @throws Exception
	 */
	public void setAccessGrant(final AccessGrant accessGrant) throws Exception {
		oauth = new OAuthConsumer(config);
		this.accessToken = accessGrant;
		isVerify = true;
	}

	/**
	 * This is the most important action. It redirects the browser to an
	 * appropriate URL which will be used for authentication with the provider
	 * that has been set using setId()
	 * 
	 * @throws Exception
	 */

	@Override
	public String getLoginRedirectURL(final String successUrl) throws Exception {
		LOG.info("Determining URL for redirection");
		setProviderState(true);
		LOG.debug("Call to fetch Request Token");
		requestToken = oauth.getRequestToken(REQUEST_TOKEN_URL, successUrl);
		StringBuilder urlBuffer = oauth.buildAuthUrl(AUTHORIZATION_URL,
				requestToken, successUrl);
		LOG.info("Redirection to following URL should happen : "
				+ urlBuffer.toString());
		return urlBuffer.toString();
	}

	/**
	 * Verifies the user when the external provider redirects back to our
	 * application.
	 * 
	 * @return Profile object containing the profile information
	 * @param request
	 *            Request object the request is received from the provider
	 * @throws Exception
	 */

	@Override
	public Profile verifyResponse(final HttpServletRequest request)
			throws Exception {
		Map<String, String> params = SocialAuthUtil
				.getRequestParametersMap(request);
		return doVerifyResponse(params);
	}

	/**
	 * Verifies the user when the external provider redirects back to our
	 * application.
	 * 
	 * 
	 * @param requestParams
	 *            request parameters, received from the provider
	 * @return Profile object containing the profile information
	 * @throws Exception
	 */

	@Override
	public Profile verifyResponse(final Map<String, String> requestParams)
			throws Exception {
		return doVerifyResponse(requestParams);
	}

	private Profile doVerifyResponse(final Map<String, String> requestParams)
			throws Exception {
		LOG.info("Verifying the authentication response from provider");
		if (!isProviderState()) {
			throw new ProviderStateException();
		}

		if (requestToken == null) {
			throw new SocialAuthException("Request token is null");
		}
		String verifier = requestParams.get(Constants.OAUTH_VERIFIER);
		if (verifier != null) {
			requestToken.setAttribute(Constants.OAUTH_VERIFIER, verifier);
		}

		LOG.debug("Call to fetch Access Token");
		accessToken = oauth.getAccessToken(ACCESS_TOKEN_URL, requestToken);
		if (scope != null) {
			accessToken.setPermission(scope);
		} else {
			accessToken.setPermission(Permission.DEFAULT);
		}
		accessToken.setProviderId(getProviderId());
		isVerify = true;
		return getProfile();
	}

	/**
	 * Gets the list of contacts of the user and their email.
	 * 
	 * @return List of profile objects representing Contacts. Only name and
	 *         email will be available
	 */

	@Override
	public List<Contact> getContactList() throws Exception {
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		LOG.info("Fetching contacts from " + CONNECTION_URL);

		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpGet(CONNECTION_URL, null, accessToken);
		} catch (Exception ie) {
			throw new SocialAuthException(
					"Failed to retrieve the contacts from " + CONNECTION_URL,
					ie);
		}
		Element root;
		try {
			root = XMLParseUtil.loadXmlResource(serviceResponse
					.getInputStream());
		} catch (Exception e) {
			throw new ServerDataException(
					"Failed to parse the profile from response."
							+ CONNECTION_URL, e);
		}
		List<Contact> contactList = new ArrayList<Contact>();
		if (root != null) {
			NodeList pList = root.getElementsByTagName("person");
			if (pList != null && pList.getLength() > 0) {
				LOG.debug("Found contacts : " + pList.getLength());
				for (int i = 0; i < pList.getLength(); i++) {
					Element p = (Element) pList.item(i);
					String fname = XMLParseUtil.getElementData(p, "first-name");
					String lname = XMLParseUtil.getElementData(p, "last-name");
					String id = XMLParseUtil.getElementData(p, "id");
					String profileUrl = XMLParseUtil.getElementData(p,
							"public-profile-url");
					if (id != null) {
						Contact cont = new Contact();
						if (fname != null) {
							cont.setFirstName(fname);
						}
						if (lname != null) {
							cont.setLastName(lname);
						}
						if (profileUrl != null) {
							cont.setProfileUrl(profileUrl);
						}
						cont.setId(id);
						contactList.add(cont);
					}
				}
			} else {
				LOG.debug("No connections were obtained from : "
						+ CONNECTION_URL);
			}
		}
		return contactList;
	}

	@Override
	public void updateStatus(final String msg) throws Exception {
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		if (msg == null || msg.trim().length() == 0) {
			throw new ServerDataException("Status cannot be blank");
		}
		if (msg.length() > 700) {
			throw new ServerDataException(
					"Status cannot be more than 700 characters.");
		}
		LOG.info("Updating status " + msg + " on " + UPDATE_STATUS_URL);
		Map<String, String> headerParams = new HashMap<String, String>();
		headerParams.put("Content-Type", "text/xml");
		String msgBody = String.format(STATUS_BODY, msg);
		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpPost(UPDATE_STATUS_URL, null,
					headerParams, msgBody, accessToken);
		} catch (Exception ie) {
			throw new SocialAuthException("Failed to update status on "
					+ UPDATE_STATUS_URL, ie);
		}
		LOG.debug("Status Updated and return status code is : "
				+ serviceResponse.getStatus());
		// return 201
	}

	/**
	 * Logout
	 */
	@Override
	public void logout() {
		requestToken = null;
		accessToken = null;
	}

	private Profile getProfile() throws Exception {
		LOG.debug("Obtaining user profile");
		Profile profile = new Profile();
		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpGet(PROFILE_URL, null, accessToken);
		} catch (Exception e) {
			throw new SocialAuthException(
					"Failed to retrieve the user profile from  " + PROFILE_URL);
		}
		if (serviceResponse.getStatus() != 200) {
			throw new SocialAuthException(
					"Failed to retrieve the user profile from  " + PROFILE_URL
							+ ". Staus :" + serviceResponse.getStatus());
		}

		Element root;
		try {
			root = XMLParseUtil.loadXmlResource(serviceResponse
					.getInputStream());
		} catch (Exception e) {
			throw new ServerDataException(
					"Failed to parse the profile from response." + PROFILE_URL,
					e);
		}

		if (root != null) {
			String fname = XMLParseUtil.getElementData(root, "first-name");
			String lname = XMLParseUtil.getElementData(root, "last-name");
			NodeList dob = root.getElementsByTagName("date-of-birth");
			if (dob != null && dob.getLength() > 0) {
				Element dobel = (Element) dob.item(0);
				if (dobel != null) {
					String y = XMLParseUtil.getElementData(dobel, "year");
					String m = XMLParseUtil.getElementData(dobel, "month");
					String d = XMLParseUtil.getElementData(dobel, "day");
					BirthDate bd = new BirthDate();
					if (m != null) {
						bd.setMonth(Integer.parseInt(m));
					}
					if (d != null) {
						bd.setDay(Integer.parseInt(d));
					}
					if (y != null) {
						bd.setYear(Integer.parseInt(y));
					}
					profile.setDob(bd);
				}
			}
			String picUrl = XMLParseUtil.getElementData(root, "picture-url");
			String id = XMLParseUtil.getElementData(root, "id");
			if (picUrl != null) {
				profile.setProfileImageURL(picUrl);
			}
			NodeList location = root.getElementsByTagName("location");
			if (location != null && location.getLength() > 0) {
				Element locationEl = (Element) location.item(0);
				String loc = XMLParseUtil.getElementData(locationEl, "name");
				if (loc != null) {
					profile.setLocation(loc);
				}
			}
			profile.setFirstName(fname);
			profile.setLastName(lname);
			profile.setValidatedId(id);
			profile.setProviderId(getProviderId());
			LOG.debug("User Profile :" + profile.toString());
			userProfile = profile;
		}
		return profile;
	}

	/**
	 * 
	 * @param p
	 *            Permission object which can be Permission.AUHTHENTICATE_ONLY,
	 *            Permission.ALL, Permission.DEFAULT
	 */
	@Override
	public void setPermission(final Permission p) {
		LOG.debug("Permission requested : " + p.toString());
		this.scope = p;
	}

	/**
	 * Makes OAuth signed HTTP request to a given URL. It attaches Authorization
	 * header with HTTP request.
	 * 
	 * @param url
	 *            URL to make HTTP request.
	 * @param methodType
	 *            Method type can be GET, POST or PUT
	 * @param params
	 *            Any additional parameters whose signature need to compute.
	 *            Only used in case of "POST" and "PUT" method type.
	 * @param headerParams
	 *            Any additional parameters need to pass as Header Parameters
	 * @param body
	 *            Request Body
	 * @return Response object
	 * @throws Exception
	 */
	@Override
	public Response api(final String url, final String methodType,
			final Map<String, String> params,
			final Map<String, String> headerParams, final String body)
			throws Exception {
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		Response response = null;
		LOG.debug("Calling URL : " + url);
		if (MethodType.GET.toString().equals(methodType)) {
			try {
				response = oauth.httpGet(url, headerParams, accessToken);
			} catch (Exception ie) {
				throw new SocialAuthException(
						"Error while making request to URL : " + url, ie);
			}
		} else if (MethodType.PUT.toString().equals(methodType)) {
			try {
				response = oauth.httpPut(url, params, headerParams, body,
						accessToken);
			} catch (Exception e) {
				throw new SocialAuthException(
						"Error while making request to URL : " + url, e);
			}
		} else if (MethodType.POST.toString().equals(methodType)) {
			try {
				response = oauth.httpPost(url, params, headerParams, body,
						accessToken);
			} catch (Exception e) {
				throw new SocialAuthException(
						"Error while making request to URL : " + url, e);
			}
		}
		return response;
	}

	/**
	 * Retrieves the user profile.
	 * 
	 * @return Profile object containing the profile information.
	 */
	@Override
	public Profile getUserProfile() throws Exception {
		if (userProfile == null && accessToken != null) {
			getProfile();
		}
		return userProfile;
	}

	@Override
	public AccessGrant getAccessGrant() {
		return accessToken;
	}

	@Override
	public String getProviderId() {
		return config.getId();
	}
}