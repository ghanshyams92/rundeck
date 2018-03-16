package rundeck.controllers

import com.dtolabs.rundeck.app.support.task.TaskCreate
import com.dtolabs.rundeck.app.support.task.TaskRequest
import com.dtolabs.rundeck.app.support.task.TaskUpdate
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import org.rundeck.core.tasks.TaskAction
import org.rundeck.core.tasks.TaskCondition
import org.rundeck.core.tasks.TaskTrigger
import rundeck.TaskRep
import rundeck.services.PluginService

class TaskController extends ControllerBase implements PluginListRequired {
    def frameworkService
    def taskService
    PluginService pluginService
    static allowedMethods = [
            'createPost': 'POST',
            'deletePost': 'POST',
            'updatePost': 'POST',
    ]
    Map<String, Class> requiredPluginTypes = [actionPlugins: TaskAction, triggerPlugins: TaskTrigger, conditionPlugins: TaskCondition]
    Collection<String> requiredPluginActionNames = ['create', 'edit', 'show', 'createPost', 'updatePost']


    def index(String project) {

        redirect(action: 'list', params: [project: project])
    }

    def cancel(String id, String project) {
        if (id && project) {
            return redirect(action: 'show', params: [id: id, project: project])
        }
        redirect(action: 'list', params: [project: project])
    }

    def list(String project) {
//        def framework = frameworkService.getRundeckFramework()

//        AuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject,scheduledExecution.project)

        //TODO: auth
        def tasks = TaskRep.findAllByProject(project)
        [tasks: tasks, project: project]
    }

    def create(String project) {

    }

    def createPost(TaskCreate input) {
        if (!requestHasValidToken()) {
            return
        }


        UserAndRolesAuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, input.project)

        //TODO: auth

        Map triggerMap = ParamsUtil.cleanMap(params.triggerConfig?.config)
        Map actionMap = ParamsUtil.cleanMap(params.actionConfig?.config)

        List conditionList = ParamsUtil.parseMapList(params.conditionList)
        conditionList = conditionList?.collect {
            [config: ParamsUtil.cleanMap(it.config ?: [:]), type: it.type]
        }

        Map userData = params.userData?.containsKey('0.key') ? ParamsUtil.parseIndexedMapParams(params.userData) :
                       [:]



        def result = taskService.createTask(authContext, input, triggerMap, conditionList, actionMap, userData)

        if (result.error) {
            //edit form
            request.error = 'Validation error'
            return render(
                    view: '/task/create',
                    model: [task: result.task, validation: result.validation, project: input.project]
            )
        }
        def task = result.task

        if (!result.registration) {
            flash.error = "Task created, but trigger could not be registered"
        } else {
            flash.message = "Task created"
        }

        return redirect(action: 'show', params: [id: task.uuid, project: input.project])
    }

    def delete(TaskRequest input) {

        show(input)
    }

    def deletePost(TaskRequest input) {
        if (!requestHasValidToken()) {
            return
        }

        //TODO: auth
        def task = TaskRep.findByProjectAndUuid(input.project, input.id)
        if (notFoundResponse(task, 'Task', input.id)) {
            return
        }

        boolean result = taskService.deleteTask(task)

        if (result) {
            flash.message = "Task $input.id was deleted"
        } else {
            flash.error = "Task $input.id was NOT deleted"
        }
        redirect(action: 'list', params: [project: input.project])
    }

    def edit(TaskRequest input) {
        show(input)
    }

    def updatePost(TaskUpdate input) {
        if (!requestHasValidToken()) {
            return
        }
        def task = TaskRep.findByProjectAndUuid(input.project, input.id)
        if (notFoundResponse(task, 'Task', input.id)) {
            return
        }
        Map triggerMap = ParamsUtil.cleanMap(params.triggerConfig?.config)
        Map actionMap = ParamsUtil.cleanMap(params.actionConfig?.config)
        List conditionList = ParamsUtil.parseMapList(params.conditionList)
        conditionList = conditionList?.collect {
            [config: ParamsUtil.cleanMap(it.config ?: [:]), type: it.type]
        }

        Map userData = params.userData?.containsKey("0.key") ? ParamsUtil.parseIndexedMapParams(params.userData) :
                       [:]

        //TODO; auth

        UserAndRolesAuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, input.project)

        def result = taskService.updateTask(authContext, task, input, triggerMap, conditionList, actionMap, userData)

        if (result.error) {
            //edit form
            request.error = 'Validation error'
            return render(
                    view: '/task/edit',
                    model: [task: task, validation: result.validation, project: input.project]
            )
        }
        if (!result.registration) {
            flash.error = "Task updated, but trigger could not be registered"
        } else {
            flash.message = "Task updated"
        }

        redirect(action: 'show', params: [project: input.project, id: input.id])
    }

    def show(TaskRequest input) {

        //TODO: auth?

        def task = TaskRep.findByProjectAndUuid(input.project, input.id)
        if (notFoundResponse(task, 'Task', input.id)) {
            return
        }

        [task: task, project: input.project]
    }

    def test(TaskRequest input) {
        def task = TaskRep.findByProjectAndUuid(input.project, input.id)
        if (notFoundResponse(task, 'Task', input.id)) {
            return
        }

        taskService.taskTriggerFired(input.id, taskService.contextForTask(task), params.data ?: [:])
        flash.message = "Task started"
        redirect(action: 'show', params: params)
    }


}

class ParamsUtil {

    /**
     * Remove map entries where the value is null or a blank string
     * @param map
     * @return
     */
    static Map cleanMap(Map map) {
        def datamap = map ? map.entrySet().
            findAll { it.value && !it.key.startsWith('_') }.
            collectEntries { [it.key, it.value] } : [:]
        //parse map type entries
        datamap = parseMapTypeEntries(datamap)
        datamap = parseEmbeddedTypeEntries(datamap)
        datamap = parseEmbeddedPluginEntries(datamap)
        datamap
    }

    /**
     * Finds all "map" type entries, and converts them using the {@link #parseIndexedMapParams(java.util.Map)}.
     * A map entry is defined as an entry PREFIX, where a key PREFIX_.type is present with value "map",
     * and a PREFIX.map is present which can be parsed as an indexed map param. (Alternately, if the PREFIX value is a Map which contains a "map" entry, that is used.)
     * All entries starting with "PREFIX." are
     * removed and an entry PREFIX is created which contains the parsed indexed map.
     * @param datamap
     */
    public static Map parseMapTypeEntries(Map datamap) {
        parseMapEntries(datamap, 'map', 'map', true)
    }

    /**
     * Finds all "map" type entries, and converts them using the {@link #parseIndexedMapParams(java.util.Map)}.
     * A map entry is defined as an entry PREFIX, where a key PREFIX_.type is present with value "map",
     * and a PREFIX.map is present which can be parsed as an indexed map param. (Alternately, if the PREFIX value is a Map which contains a "map" entry, that is used.)
     * All entries starting with "PREFIX." are
     * removed and an entry PREFIX is created which contains the parsed indexed map.
     * @param datamap
     */
    public static Map parseEmbeddedTypeEntries(Map datamap) {
        parseMapEntries(datamap, 'embedded', 'config', false)
    }
    /**
     * Finds all "embeddedPlugin" type entries, and expects it to contains a set of 'config' entries (config map values),
     * and a 'type' entry (plugin provider type)
     * @param datamap
     */
    public static Map parseEmbeddedPluginEntries(Map datamap) {
        parseMapEntries(datamap, 'embeddedPlugin', 'config', false) { data, key, map ->
            def type = data[key + '.type'] ?: (data[key] instanceof Map) ? data[key]['type'] : null
            type ? [type: type, config: map] : map
        }
    }

    public static Map parseMapEntries(
        Map datamap,
        String typeVal,
        String suffix,
        Boolean expectIndexed,
        Closure transform = null
    ) {
        def outmap = new HashMap(datamap)
        def types = datamap.keySet().findAll { it.endsWith('._type') }
        types.each { String typek ->
            def keyname = typek.substring(0, typek.length() - ('._type'.length()))
            def thetype = datamap.get(typek)
            def mapval = datamap.get(keyname + '.' + suffix)
            if (!mapval && datamap.get(keyname) instanceof Map) {
                mapval = datamap.get(keyname).get(suffix)
            }
            if (!mapval) {
                //collect prefixes
                def testprefix = keyname + '.' + suffix + '.'
                def list = datamap.keySet().findAll { it.startsWith(testprefix) }
                if (list) {
                    mapval = list.collectEntries { String k ->
                        def val = datamap[k]
                        [k.substring(testprefix.length()), val]
                    }
                }
            }
            if (thetype == typeVal && (mapval instanceof Map)) {
                def pmap = expectIndexed ? parseIndexedMapParams(mapval) : mapval

                outmap[keyname] = transform ? transform(datamap, keyname, pmap) : pmap
                def entries = datamap.keySet().findAll { it.startsWith(keyname + '.') }
                entries.each { outmap.remove(it) }
            } else if (thetype == typeVal && !mapval) {
                //empty map
                outmap[keyname] = [:]
                def entries = datamap.keySet().findAll { it.startsWith(keyname + '.') }
                entries.each { outmap.remove(it) }

            }

        }
        outmap
    }

    /**
     *
     * @param data
     * @return
     */
    static List parseMapList(Map data) {
        def entries = [data.get("_indexes")].flatten()
        List result = []

        entries.each { index ->
            def map = data.get("entry[${index}]".toString())
            if (map) {
                result << cleanMap(map)
            }
        }
        result
    }
    /**
     * Parse input data with index key/value entries into a map.
     * If a key of the form "0.key" exists,  look for 0.value, to find a key/value pair, then
     * increment the index until no corresponding key is found. Empty keys are skipped, but
     * considered valid indexes. Empty or null values are interpreted as empty strings.
     *
     * @param map
     * @return the key/value data as a single map, or empty map if none is found
     */
    static Map parseIndexedMapParams(Map map) {
        int index = 0
        def data = [:]
        while (map != null && map.containsKey("${index}.key".toString())) {

            def key = map["${index}.key".toString()]
            def value = map["${index}.value".toString()]
            if (key) {
                data[key] = value?.toString() ?: ''
            }
            index++
        }
        data
    }
}