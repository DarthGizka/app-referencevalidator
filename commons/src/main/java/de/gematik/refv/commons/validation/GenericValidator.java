/*
 * Copyright (c) 2023 gematik GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.gematik.refv.commons.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import de.gematik.refv.commons.Profile;
import de.gematik.refv.commons.ReferencedProfileLocator;
import de.gematik.refv.commons.configuration.DependencyList;
import de.gematik.refv.commons.configuration.ValidationModuleConfiguration;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class GenericValidator {
    public static final String ERR_REFV_PROFILE_OUTSIDE_OF_VALIDITY_PERIOD = "REFV_PROFILE_OUTSIDE_OF_VALIDITY_PERIOD";
    public static final String ERR_REFV_NO_CREATION_DATE_IN_RESOURCE = "REFV_NO_CREATION_DATE_IN_RESOURCE";
    public static final String ERR_REFV_PARSE_ERROR = "REFV_PARSE_ERROR";
    public static final String WARN_REFV_VALIDITY_PERIOD_CHECK_DISABLED = "REFV_VALIDITY_PERIOD_CHECK_DISABLED";
    private static final String WARN_REFV_PASSED_PROFILE_DIFFERS_FROM_META_PROFILE = "REFV_WARN_PASSED_PROFILE_DIFFERS_FROM_META_PROFILE";
    private static final String ERR_REFV_WRONG_ENCODING = "REFV_WRONG_ENCODING";
    private final FhirContext fhirContext;

    private final ReferencedProfileLocator referencedProfileLocator;

    private final HapiFhirValidatorFactory hapiFhirValidatorFactory;

    private final SeverityLevelTransformer severityLevelTransformator;
    private final ConcurrentHashMap<DependencyList, FhirValidator> hapiFhirValidatorCache;

    public GenericValidator(FhirContext context) {
        this.fhirContext = context;
        this.referencedProfileLocator = new ReferencedProfileLocator();
        this.hapiFhirValidatorFactory = new HapiFhirValidatorFactory(fhirContext);
        this.severityLevelTransformator = new SeverityLevelTransformer();
        this.hapiFhirValidatorCache = new ConcurrentHashMap<>();
    }

    public ValidationResult validate(
            @NonNull
            String resourceBody,
            @NonNull
            ValidationModuleConfiguration configuration) throws IllegalArgumentException {
        return validate(resourceBody, configuration, ValidationOptions.getDefaults());
    }

    public ValidationResult validate(
            @NonNull
            String resourceBody,
            @NonNull
            ValidationModuleConfiguration configuration,
            @NonNull ValidationOptions validationOptions) throws IllegalArgumentException {

        if(!validateEncoding(resourceBody, configuration, validationOptions))
            return ValidationResult.createInstance(ResultSeverityEnum.ERROR, ERR_REFV_WRONG_ENCODING, String.format("Wrong instance encoding. Allowed encodings: %s", String.join(",", getAcceptedEncodings(configuration, validationOptions))));

        Profile profileForValidation;
        String userDefinedProfile = null;

        Profile profileInResource = getProfileInResource(resourceBody, configuration);
        if(validationOptions.getProfiles().isEmpty()) {
            profileForValidation = profileInResource;
        }
        else {
            userDefinedProfile = validationOptions.getProfiles().get(0); // Only one user defined profile is supported at the moment
            log.warn("Profile for validation has been passed by user: " + userDefinedProfile);
            profileForValidation = Profile.parse(userDefinedProfile);
        }

        log.info("Validating against {}...", profileForValidation);

        var profileConfiguration =  configuration.getSupportedProfileConfigurationOrThrow(profileForValidation);
        String creationDateLocator = profileConfiguration.getCreationDateLocator();

        var dependencyLists = configuration.getDependencyListsForProfile(profileForValidation);

        // Assumption: each profile has at least one dependency list configured

        if(StringUtils.isEmpty(creationDateLocator)) {
            // Use Case 1. Profile without configured locator --> use latest dependencies
            log.warn("Could not retrieve creation date for profile {}: no locator expression defined. Using latest dependencies...", profileInResource);
            var dependencyList = dependencyLists.getLatestDependencyList();
            return validateUsingDependencyList(resourceBody, configuration, dependencyList, profileInResource, userDefinedProfile);
        }


        // Use Case 2. Profile has a configured locator
        try {
            var resourceCreationDateOptional = new ResourceCreationDateLocator(fhirContext).findCreationDateIn(resourceBody, creationDateLocator);

            log.debug("Resource creation date: {}", resourceCreationDateOptional);

            if(resourceCreationDateOptional.isEmpty()) {
                // Use Case 2.1 Creation date could not be located in the resource --> error
                return ValidationResult.createInstance(ResultSeverityEnum.ERROR, ERR_REFV_NO_CREATION_DATE_IN_RESOURCE, String.format("Could not determine the creation date of the resource using the configured expression %s", creationDateLocator));
            }

            // Use Case 2.2 Creation date could be located in the resource
            var dependencyListOptional = dependencyLists.getDependencyListValidAt(resourceCreationDateOptional.get());
            if (dependencyListOptional.isPresent())
                // Use Case 2.2.1 Dependency list for the resource creation date is present
                return validateUsingDependencyList(resourceBody, configuration, dependencyListOptional.get(), profileInResource, userDefinedProfile);

            // Use Case 2.2.2 No Dependency list is found for the creation date
            if (isValidateProfileValidityPeriod(validationOptions))
                // Use Case 2.2.2.1 ValidityPeriodCheck is turned on and no dependency lists were found -> Profile is outside of validity period!
                return ValidationResult.createInstance(ResultSeverityEnum.ERROR, ERR_REFV_PROFILE_OUTSIDE_OF_VALIDITY_PERIOD, String.format("Profile %s is invalid for the creation date of the resource (%s)", profileInResource, resourceCreationDateOptional.get()));

            // Use Case 2.2.2.2 ValidityPeriodChek is turned off -> use latest dependencies
            var result = validateUsingDependencyList(resourceBody, configuration, dependencyLists.getLatestDependencyList(), profileInResource, userDefinedProfile);
            addWarningAboutDeactivatedValidityPeriodCheckTo(result);
            return result;

        } catch (DataFormatException e) {
            log.debug("Parse error", e);
            return ValidationResult.createInstance(ResultSeverityEnum.ERROR, ERR_REFV_PARSE_ERROR, e.getMessage());
        }
    }

    private boolean validateEncoding(String resourceBody, ValidationModuleConfiguration configuration, de.gematik.refv.commons.validation.ValidationOptions validationOptions) {
        List<String> acceptedEncodings = getAcceptedEncodings(configuration, validationOptions);

        if(acceptedEncodings.isEmpty())
            return true;

        EncodingEnum encoding = EncodingEnum.detectEncodingNoDefault(resourceBody);

        if(acceptedEncodings.contains(Constants.FORMAT_XML) && encoding == EncodingEnum.XML)
            return true;

        if(acceptedEncodings.contains(Constants.FORMAT_JSON) && encoding == EncodingEnum.JSON)
            return true;

        log.warn("Unknown resource encoding: {}", encoding);
        return false;
    }

    private static List<String> getAcceptedEncodings(ValidationModuleConfiguration configuration, ValidationOptions validationOptions) {
        var encodings = !validationOptions.getAcceptedEncodings().isEmpty() ? validationOptions.getAcceptedEncodings() : configuration.getAcceptedEncodings();
        return encodings.stream().map(String::toLowerCase).collect(Collectors.toList());
    }

    private void addWarningAboutDeactivatedValidityPeriodCheckTo(ValidationResult result) {
        var m = new SingleValidationMessage();
        m.setSeverity(ResultSeverityEnum.WARNING);
        m.setMessageId(WARN_REFV_VALIDITY_PERIOD_CHECK_DISABLED);
        m.setMessage("Validity period check has been disabled by user");
        result.getValidationMessages().add(m);
    }

    private ValidationResult validateUsingDependencyList(String resourceBody, ValidationModuleConfiguration configuration, DependencyList dependencyList, Profile profileInResource, String userDefinedProfile) {
        log.debug("Applying dependency list: {}", dependencyList);

        var fhirValidator = hapiFhirValidatorCache.computeIfAbsent(dependencyList, k ->
                hapiFhirValidatorFactory.createInstance(
                dependencyList.getPackages(),
                dependencyList.getPatches(),
                configuration
        ));

        var options = new ca.uhn.fhir.validation.ValidationOptions();
        if(userDefinedProfile != null)
            options.addProfile(userDefinedProfile);
        var intermediateResult = fhirValidator.validateWithResult(resourceBody, options);

        log.debug("Pre-Transformation ValidationResult: Valid: {}, Messages: {}", intermediateResult.isSuccessful(), intermediateResult.getMessages());

        var filteredMessages = severityLevelTransformator.applyTransformations(intermediateResult.getMessages(), dependencyList.getValidationMessageTransformations());
        
        if(userDefinedProfile != null && !userDefinedProfile.equals(profileInResource.toString())) {
            SingleValidationMessage m = new SingleValidationMessage();
            m.setSeverity(ResultSeverityEnum.WARNING);
            m.setMessage(String.format("Resource meta.profile differs from the passed profile for validation: meta.profile=%s; passed=%s", profileInResource, userDefinedProfile));
            m.setMessageId(WARN_REFV_PASSED_PROFILE_DIFFERS_FROM_META_PROFILE);
            filteredMessages.add(m);
        }
        
        return new ValidationResult(filteredMessages);
    }

    private boolean isValidateProfileValidityPeriod(de.gematik.refv.commons.validation.ValidationOptions validationOptions) {
        return validationOptions.getProfileValidityPeriodCheckStrategy() == ProfileValidityPeriodCheckStrategy.VALIDATE;
    }

    private Profile getProfileInResource(String resourceBody, ValidationModuleConfiguration configuration) {
        // Use custom performance-optimized profile extraction due to issues with HAPI XML Parser
        // Parsed XML files are transformed to JSON internally by HAPI and some elements such as XML comments are processed wrongly
        Optional<Profile> profileOrEmpty = referencedProfileLocator.locate(resourceBody, configuration);
        if (profileOrEmpty.isEmpty())
            throw new IllegalArgumentException("FHIR resources without a referenced profile are currently unsupported");
        return profileOrEmpty.get();
    }

}
