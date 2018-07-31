import { ajax } from "discourse/lib/ajax";
const _loaded = {};
const _loading = {};

function loadWithTag(path, cb) {
  const head = document.getElementsByTagName("head")[0];

  let finished = false;
  let s = document.createElement("script");
  s.src = path;
  if (Ember.Test) {
    Ember.Test.registerWaiter(() => finished);
  }
  head.appendChild(s);

  s.onload = s.onreadystatechange = function(_, abort) {
    finished = true;
    if (
      abort ||
      !s.readyState ||
      s.readyState === "loaded" ||
      s.readyState === "complete"
    ) {
      s = s.onload = s.onreadystatechange = null;
      if (!abort) {
        Ember.run(null, cb);
      }
    }
  };
}

export function loadCSS(url) {
  return loadScript(url, { css: true });
}

export default function loadScript(url, opts) {
  // TODO: Remove this once plugins have been updated not to use it:
  if (url === "defer/html-sanitizer-bundle") {
    return Ember.RSVP.Promise.resolve();
  }

  opts = opts || {};

  $("script").each((i, tag) => {
    const src = tag.getAttribute("src");

    if (src && (opts.scriptTag || src !== url)) {
      _loaded[tag.getAttribute("src")] = true;
    }
  });

  return new Ember.RSVP.Promise(function(resolve) {
    url = Discourse.getURL(url);

    // If we already loaded this url
    if (_loaded[url]) {
      return resolve();
    }
    if (_loading[url]) {
      return _loading[url].then(resolve);
    }

    let done;
    _loading[url] = new Ember.RSVP.Promise(function(_done) {
      done = _done;
    });

    _loading[url].then(function() {
      delete _loading[url];
    });

    const cb = function(data) {
      if (opts && opts.css) {
        $("head").append("<style>" + data + "</style>");
      }
      done();
      resolve();
      _loaded[url] = true;
    };

    let cdnUrl = url;

    // Scripts should always load from CDN
    // CSS is type text, to accept it from a CDN we would need to handle CORS
    if (!opts.css && Discourse.CDN && url[0] === "/" && url[1] !== "/") {
      cdnUrl = Discourse.CDN.replace(/\/$/, "") + url;
    }

    // Some javascript depends on the path of where it is loaded (ace editor)
    // to dynamically load more JS. In that case, add the `scriptTag: true`
    // option.
    if (opts.scriptTag) {
      if (Ember.testing) {
        throw new Error(
          `In test mode scripts cannot be loaded async ${cdnUrl}`
        );
      }
      loadWithTag(cdnUrl, cb);
    } else {
      ajax({
        url: cdnUrl,
        dataType: opts.css ? "text" : "script",
        cache: true
      }).then(cb);
    }
  });
}
