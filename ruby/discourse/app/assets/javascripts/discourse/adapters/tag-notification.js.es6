import RESTAdapter from "discourse/adapters/rest";

export default RESTAdapter.extend({
  pathFor(store, type, id) {
    return "/tags/" + id + "/notifications";
  }
});
