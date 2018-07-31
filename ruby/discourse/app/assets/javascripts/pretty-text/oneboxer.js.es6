let timeout;
const loadingQueue = [];
const localCache = {};
const failedCache = {};

function resolveSize(img) {
  $(img).addClass("size-resolved");

  if (img.width > 0 && img.width === img.height) {
    $(img).addClass("onebox-avatar");
  }
}

// Detect square images and apply smaller onebox-avatar class
function applySquareGenericOnebox($elem, normalizedUrl) {
  if (!$elem.hasClass("whitelistedgeneric")) {
    return;
  }

  let $img = $elem.find(".onebox-body img.thumbnail");
  let img = $img[0];

  // already resolved... skip
  if ($img.length !== 1 || $img.hasClass("size-resolved")) {
    return;
  }

  if (img.complete) {
    resolveSize(img, $elem, normalizedUrl);
  } else {
    $img.on("load.onebox", () => {
      resolveSize(img, $elem, normalizedUrl);
      $img.off("load.onebox");
    });
  }
}

function loadNext(ajax) {
  if (loadingQueue.length === 0) {
    timeout = null;
    return;
  }

  let timeoutMs = 150;
  let removeLoading = true;
  const { url, refresh, $elem, categoryId, topicId } = loadingQueue.shift();

  // Retrieve the onebox
  return ajax("/onebox", {
    dataType: "html",
    data: {
      url,
      refresh,
      category_id: categoryId,
      topic_id: topicId
    },
    cache: true
  })
    .then(
      html => {
        let $html = $(html);
        localCache[normalize(url)] = $html;
        $elem.replaceWith($html);
        applySquareGenericOnebox($html, normalize(url));
      },
      result => {
        if (result && result.jqXHR && result.jqXHR.status === 429) {
          timeoutMs = 2000;
          removeLoading = false;
          loadingQueue.unshift({ url, refresh, $elem, categoryId, topicId });
        } else {
          failedCache[normalize(url)] = true;
        }
      }
    )
    .finally(() => {
      timeout = Ember.run.later(() => loadNext(ajax), timeoutMs);
      if (removeLoading) {
        $elem.removeClass("loading-onebox");
        $elem.data("onebox-loaded");
      }
    });
}

// Perform a lookup of a onebox based an anchor $element.
// It will insert a loading indicator and remove it when the loading is complete or fails.
export function load({
  elem,
  refresh = true,
  ajax,
  synchronous = false,
  categoryId,
  topicId
}) {
  const $elem = $(elem);

  // If the onebox has loaded or is loading, return
  if ($elem.data("onebox-loaded")) return;
  if ($elem.hasClass("loading-onebox")) return;

  const url = elem.href;

  // Unless we're forcing a refresh...
  if (!refresh) {
    // If we have it in our cache, return it.
    const cached = localCache[normalize(url)];
    if (cached) return cached.prop("outerHTML");

    // If the request failed, don't do anything
    const failed = failedCache[normalize(url)];
    if (failed) return;
  }

  // Add the loading CSS class
  $elem.addClass("loading-onebox");

  // Add to the loading queue
  loadingQueue.push({ url, refresh, $elem, categoryId, topicId });

  // Load next url in queue
  if (synchronous) {
    return loadNext(ajax);
  } else {
    timeout = timeout || Ember.run.later(() => loadNext(ajax), 150);
  }
}

// Sometimes jQuery will return URLs with trailing slashes when the
// `href` didn't have them.
function normalize(url) {
  return url.replace(/\/$/, "");
}

export function lookupCache(url) {
  const cached = localCache[normalize(url)];
  return cached && cached.prop("outerHTML");
}
