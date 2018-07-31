export function extractError(error, defaultMessage) {
  if (error instanceof Error) {
    Ember.Logger.error(error.stack);
  }

  if (typeof error === "string") {
    Ember.Logger.error(error);
  }

  if (error.jqXHR) {
    error = error.jqXHR;
  }

  let parsedError, parsedJSON;

  if (error.responseJSON) {
    parsedJSON = error.responseJSON;
  }

  if (!parsedJSON && error.responseText) {
    try {
      parsedJSON = $.parseJSON(error.responseText);
    } catch (ex) {
      // in case the JSON doesn't parse
      Ember.Logger.error(ex.stack);
    }
  }

  if (parsedJSON) {
    if (parsedJSON.errors && parsedJSON.errors.length > 0) {
      parsedError = parsedJSON.errors.join("<br>");
    } else if (parsedJSON.error) {
      parsedError = parsedJSON.error;
    } else if (parsedJSON.failed) {
      parsedError = parsedJSON.message;
    }
  }

  if (!parsedError) {
    if (error.status && error.status >= 400) {
      parsedError = error.status + " " + error.statusText;
    }
  }

  return parsedError || defaultMessage || I18n.t("generic_error");
}

export function throwAjaxError(undoCallback) {
  return function(error) {
    // If we provided an `undo` callback
    if (undoCallback) {
      undoCallback(error);
    }
    throw extractError(error);
  };
}

export function popupAjaxError(error) {
  if (error && error._discourse_displayed) {
    return;
  }
  bootbox.alert(extractError(error));

  error._discourse_displayed = true;

  // We re-throw in a catch to not swallow the exception
  throw error;
}
