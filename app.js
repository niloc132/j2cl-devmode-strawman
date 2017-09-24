goog.provide('app');
goog.require('com.vertispan.draw.connected.client.FlowChartEntryPoint');
goog.require('jsinterop.base.InternalJsUtil');

goog.scope(function() {

    goog.module.get('jsinterop.base.InternalJsUtil').getIndexed=function(array, index) { return array[index]; };
    goog.module.get('jsinterop.base.InternalJsUtil').getLength=function(array) { return array.length; };
    ///*
    // * @export
    // */
    //app.go = function go() {
        (new (goog.module.get('com.vertispan.draw.connected.client.FlowChartEntryPoint'))).m_onModuleLoad__();
    //};
});

