package io.mosip.authentication.common.service.filter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import javax.servlet.FilterConfig;
import javax.servlet.ServletException;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import io.mosip.authentication.common.manager.IdAuthFraudAnalysisEventManager;
import io.mosip.authentication.common.service.transaction.manager.IdAuthSecurityManager;
import io.mosip.authentication.core.constant.DomainType;
import io.mosip.authentication.core.constant.IdAuthCommonConstants;
import io.mosip.authentication.core.constant.IdAuthenticationErrorConstants;
import io.mosip.authentication.core.exception.IdAuthenticationAppException;
import io.mosip.authentication.core.exception.IdAuthenticationBusinessException;
import io.mosip.authentication.core.logger.IdaLogger;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.StringUtils;

/**
 * The Class BaseAuthFilter - The Base Auth Filter that does all necessary
 * authentication/authorization before allowing the request to the respective
 * controllers.
 * 
 * @author Manoj SP
 * @author Sanjay Murali
 */
@Component
public abstract class BaseAuthFilter extends BaseIDAFilter {

	/** The Constant BASE_AUTH_FILTER. */
	private static final String BASE_AUTH_FILTER = "BaseAuthFilter";

	/** The Constant EVENT_FILTER. */
	private static final String EVENT_FILTER = "Event_filter";

	/** The mosip logger. */
	private static Logger mosipLogger = IdaLogger.getLogger(BaseAuthFilter.class);

	@Autowired
	private IdAuthSecurityManager securityManager;
	
	@Autowired
	private IdAuthFraudAnalysisEventManager fraudEventManager;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		super.init(filterConfig);
		WebApplicationContext context = WebApplicationContextUtils
				.getRequiredWebApplicationContext(filterConfig.getServletContext());
		securityManager = context.getBean(IdAuthSecurityManager.class);
		fraudEventManager = context.getBean(IdAuthFraudAnalysisEventManager.class);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.authentication.service.filter.BaseIDAFilter#consumeRequest(io.mosip.
	 * authentication.service.filter.ResettableStreamHttpServletRequest)
	 */
	@Override
	protected void consumeRequest(ResettableStreamHttpServletRequest requestWrapper, Map<String, Object> requestBody)
			throws IdAuthenticationAppException {
		super.consumeRequest(requestWrapper, requestBody);
		authenticateRequest(requestWrapper);

		decipherAndValidateRequest(requestWrapper, requestBody);

	}

	/**
	 * Decipher and validate request - Method used to decipher the input stream
	 * request and validate it using {@link validateDecipheredRequest} method.
	 *
	 * @param requestWrapper the request wrapper
	 * @param requestBody    the request body
	 * @throws IdAuthenticationAppException      the id authentication app exception
	 * @throws IdAuthenticationBusinessException
	 */
	protected void decipherAndValidateRequest(ResettableStreamHttpServletRequest requestWrapper,
			Map<String, Object> requestBody) throws IdAuthenticationAppException {
		try {
			requestWrapper.resetInputStream();
			Map<String, Object> decipherRequest = decipherRequest(requestBody);
			validateDecipheredRequest(requestWrapper, decipherRequest);
			String requestAsString = mapper.writeValueAsString(decipherRequest);
//			mosipLogger.debug(IdAuthCommonConstants.SESSION_ID, EVENT_FILTER, BASE_AUTH_FILTER, "Input Request: \n" + requestAsString);
			requestWrapper.replaceData(requestAsString.getBytes());
		} catch (IOException e) {
			mosipLogger.error(IdAuthCommonConstants.SESSION_ID, EVENT_FILTER, BASE_AUTH_FILTER, ExceptionUtils.getStackTrace(e));
			throw new IdAuthenticationAppException(IdAuthenticationErrorConstants.UNABLE_TO_PROCESS);
		}
	}

	protected void verifyJwsData(String jwsSignature) throws IdAuthenticationAppException {
		if (!verifySignature(jwsSignature, null, DomainType.JWT_DATA.getType())) {
			mosipLogger.error(IdAuthCommonConstants.SESSION_ID, EVENT_FILTER, BASE_AUTH_FILTER, "Invalid certificate");
			throw new IdAuthenticationAppException(IdAuthenticationErrorConstants.INVALID_CERTIFICATE);
		}
	}

	protected String getPayloadFromJwsSingature(String jws) {
		String[] split = jws.split("\\.");
		if (split.length >= 2) {
			return split[1];
		}
		return jws;
	}

	protected boolean verifySignature(String jwsSignature, String requestData, String domain) {
		if (isSignatureVerificationRequired()) {
			try {
				return securityManager.verifySignature(jwsSignature, domain, requestData, isTrustValidationRequired());
			} catch (Exception e) {
				mosipLogger.error(IdAuthCommonConstants.SESSION_ID, "verifySignature", BASE_AUTH_FILTER,
						"Invalid JWS data: " + e.getMessage());
				return false;
			}
		}
		return true;
	}

	/**
	 * validateDecipheredRequest - Method used to validate the input stream request
	 * by validating the policy, partner and MISP id of the authenticating partner
	 * once the request is decoded and deciphered.
	 *
	 * @param requestWrapper  {@link ResettableStreamHttpServletRequest}
	 * @param decipherRequest the request got after decode and decipher the input
	 *                        stream
	 * @throws IdAuthenticationAppException      the id authentication app exception
	 * @throws IdAuthenticationBusinessException
	 */
	protected abstract void validateDecipheredRequest(ResettableStreamHttpServletRequest requestWrapper,
			Map<String, Object> decipherRequest) throws IdAuthenticationAppException;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.authentication.service.filter.BaseIDAFilter#authenticateRequest(io.
	 * mosip.authentication.service.filter.ResettableStreamHttpServletRequest)
	 */
	@Override
	protected void authenticateRequest(ResettableStreamHttpServletRequest requestWrapper)
			throws IdAuthenticationAppException {
		validateSignature(requestWrapper.getHeader("signature"), requestWrapper);
		String consentToken = requestWrapper.getHeader("Authorization");
		if (StringUtils.isEmpty(consentToken)) {
			mosipLogger.error(IdAuthCommonConstants.SESSION_ID, EVENT_FILTER, BASE_AUTH_FILTER,
					"consent token Auth is empty or null");
			throw new IdAuthenticationAppException(
					IdAuthenticationErrorConstants.MISSING_INPUT_PARAMETER.getErrorCode(),
					String.format(IdAuthenticationErrorConstants.MISSING_INPUT_PARAMETER.getErrorMessage(),
							"Authorization - header"));
		}
	}

	private void validateSignature(String signature, ResettableStreamHttpServletRequest requestWrapper)
			throws IdAuthenticationAppException {
		try {
			if (isSignatureVerificationRequired()) {
				if (StringUtils.isEmpty(signature)) {
					mosipLogger.error(IdAuthCommonConstants.SESSION_ID, EVENT_FILTER, BASE_AUTH_FILTER,
							"signature is empty or null");
					throw new IdAuthenticationAppException(
							IdAuthenticationErrorConstants.MISSING_INPUT_PARAMETER.getErrorCode(),
							String.format(IdAuthenticationErrorConstants.MISSING_INPUT_PARAMETER.getErrorMessage(),
									"signature - header"));
				} else {
					String requestData = IOUtils.toString(requestWrapper.getInputStream(), Charset.defaultCharset());
					requestWrapper.resetInputStream();
					if (!verifySignature(signature,
							requestData,
							DomainType.AUTH.getType())) {
						mosipLogger.error(IdAuthCommonConstants.SESSION_ID, EVENT_FILTER, BASE_AUTH_FILTER,
								"signature JWS failed");
						fraudEventManager.analyseDigitalSignatureFailure(requestWrapper.getRequestURI(), requestData);
						throw new IdAuthenticationAppException(IdAuthenticationErrorConstants.DSIGN_FALIED);
					}
				}
				requestWrapper.resetInputStream();
			}
		} catch (IOException e) {
			throw new IdAuthenticationAppException(IdAuthenticationErrorConstants.UNABLE_TO_PROCESS, e);
		}
	}

	/**
	 * Decode method is used to decode the encoded string.
	 *
	 * @param stringToDecode the encoded string
	 * @return the object the decoded string
	 * @throws IdAuthenticationAppException the id authentication app exception
	 */
	protected static Object decode(String stringToDecode) throws IdAuthenticationAppException {
		try {
			if (Objects.nonNull(stringToDecode)) {
				return CryptoUtil.decodeBase64(stringToDecode);
			} else {
				return stringToDecode;
			}
		} catch (IllegalArgumentException e) {
			mosipLogger.error(IdAuthCommonConstants.SESSION_ID, EVENT_FILTER, BASE_AUTH_FILTER, e.getMessage());
			throw new IdAuthenticationAppException(IdAuthenticationErrorConstants.DSIGN_FALIED, e);
		}
	}

	/**
	 * decipherRequest method is used to get the deciphered request from the encoded
	 * and enciphered request passed by the authenticating partner.
	 *
	 * @param requestBody the encoded and enciphered request body
	 * @return the map the decoded and deciphered request body
	 * @throws IdAuthenticationAppException the id authentication app exception
	 */
	protected Map<String, Object> decipherRequest(Map<String, Object> requestBody) throws IdAuthenticationAppException {
		return requestBody;
	}

	/**
	 * encipherResponse method is used to encoded and encrypt the response received
	 * while returning the KYC response.
	 *
	 * @param responseBody the response received after authentication
	 * @return the map the final encoded and enciphered response
	 * @throws IdAuthenticationAppException the id authentication app exception
	 */
	protected Map<String, Object> encipherResponse(Map<String, Object> responseBody)
			throws IdAuthenticationAppException {
		return responseBody;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.authentication.service.filter.BaseIDAFilter#transformResponse(java.
	 * util.Map)
	 */
	@Override
	protected Map<String, Object> transformResponse(Map<String, Object> responseMap)
			throws IdAuthenticationAppException {
		return encipherResponse(responseMap);
	}

	/**
	 * validateRequestHMAC method is used to validate the HMAC of the request with
	 * the deciphered request block and requestHMAC received in the request body.
	 *
	 * @param requestHMAC the requestHMAC received in the request body
	 * @param request     the generated HMAC computed once the request is decoded
	 *                    and deciphered
	 * @throws IdAuthenticationAppException the id authentication app exception
	 */
	protected void validateRequestHMAC(String requestHMAC, String request) throws IdAuthenticationAppException {
		if (!requestHMAC.contentEquals(
				IdAuthSecurityManager.generateHashAndDigestAsPlainText(request.getBytes(StandardCharsets.UTF_8)))) {
			throw new IdAuthenticationAppException(IdAuthenticationErrorConstants.HMAC_VALIDATION_FAILED);
		}
	}
}
