let _started = false;

const cache = {};
let transitionCount = 0;

export function setTransient(key, data, count) {
  cache[key] = { data, target: transitionCount + count };
}

export function getTransient(key) {
  return cache[key];
}

export function startPageTracking(router, appEvents) {
  if (_started) {
    return;
  }

  router.on("didTransition", function() {
    this.send("refreshTitle");
    const url = Discourse.getURL(this.get("url"));

    // Refreshing the title is debounced, so we need to trigger this in the
    // next runloop to have the correct title.
    Ember.run.next(() => {
      let title = Discourse.get("_docTitle");
      appEvents.trigger("page:changed", {
        url,
        title,
        currentRouteName: router.get("currentRouteName")
      });
    });

    transitionCount++;
    _.each(cache, (v, k) => {
      if (v && v.target && v.target < transitionCount) {
        delete cache[k];
      }
    });
  });
  _started = true;
}

const _gtmPageChangedCallbacks = [];

export function addGTMPageChangedCallback(callback) {
  _gtmPageChangedCallbacks.push(callback);
}

export function googleTagManagerPageChanged(data) {
  let gtmData = {
    event: "virtualPageView",
    page: {
      title: data.title,
      url: data.url
    }
  };

  _.each(_gtmPageChangedCallbacks, callback => callback(gtmData));

  window.dataLayer.push(gtmData);
}
