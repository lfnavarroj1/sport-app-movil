package com.lfnj.sport_app.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import retrofit2.http.GET
import retrofit2.http.POST
import java.lang.reflect.Method

/**
 * Lector mínimo de la copia del OpenAPI del BFF móvil para pruebas de contrato.
 * Compara los modelos `@Serializable` y las operaciones Retrofit del cliente con el contrato.
 */
class OpenApiContract private constructor(private val document: JsonObject) {
    val version: String = document.getValue("info").jsonObject.getValue("version").jsonPrimitive.content

    private val schemas: JsonObject =
        document.getValue("components").jsonObject.getValue("schemas").jsonObject

    private val paths: JsonObject = document.getValue("paths").jsonObject

    fun schemaProperties(schema: String): Set<String> =
        schema(schema)["properties"]?.jsonObject?.keys ?: emptySet()

    fun schemaRequired(schema: String): Set<String> =
        schema(schema)["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    fun propertySchema(schema: String, property: String): JsonObject =
        schema(schema).getValue("properties").jsonObject.getValue(property).jsonObject

    fun enumValues(schema: String, property: String): Set<String> =
        propertySchema(schema, property)["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    fun responseCodes(method: String, path: String): Set<String> =
        operation(method, path).getValue("responses").jsonObject.keys

    fun hasOperation(method: String, path: String): Boolean =
        paths[path]?.jsonObject?.containsKey(method) == true

    /** Parámetros `in` = [location] de la operación (`query`, `header`, `path`), en minúsculas. */
    fun parameters(method: String, path: String, location: String): Set<String> =
        operation(method, path)["parameters"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it.getValue("in").jsonPrimitive.content == location }
            .map { it.getValue("name").jsonPrimitive.content.lowercase() }
            .toSet()

    /**
     * Como [assertModelMatches], para el cuerpo JSON en línea de la operación (los cuerpos de
     * HU032 no están en `components`).
     */
    fun assertRequestBodyMatches(method: String, path: String, serializer: KSerializer<*>) {
        val schema = operation(method, path).getValue("requestBody").jsonObject.getValue("content").jsonObject
            .getValue("application/json").jsonObject.getValue("schema").jsonObject
        val properties = schema["properties"]?.jsonObject?.keys.orEmpty()
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        val descriptor = serializer.descriptor
        val modelNames = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        assertTrue("$method $path: propiedades ausentes del contrato: ${modelNames - properties}", (modelNames - properties).isEmpty())
        assertTrue("$method $path: faltan obligatorias: ${required - modelNames}", (required - modelNames).isEmpty())
    }

    /** Nombre del esquema de `components` al que remite el cuerpo JSON de la operación (`$ref`), o `null`. */
    fun requestBodySchemaRef(method: String, path: String): String? =
        operation(method, path).getValue("requestBody").jsonObject.getValue("content").jsonObject
            .getValue("application/json").jsonObject.getValue("schema").jsonObject["\$ref"]
            ?.jsonPrimitive?.content?.substringAfterLast('/')

    /**
     * Verifica que cada propiedad del modelo exista en el esquema y que el modelo declare todas
     * las propiedades obligatorias del esquema. Los nombres se toman del descriptor serializado.
     */
    fun assertModelMatches(schema: String, serializer: KSerializer<*>) {
        val descriptor = serializer.descriptor
        val modelNames = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        val properties = schemaProperties(schema)
        val unknown = modelNames - properties
        assertTrue("$schema: el modelo declara propiedades ausentes del contrato: $unknown", unknown.isEmpty())
        val missing = schemaRequired(schema) - modelNames
        assertTrue("$schema: faltan propiedades obligatorias del contrato: $missing", missing.isEmpty())
    }

    /** Verifica que todas las operaciones Retrofit de [api] existan en el contrato con su método HTTP. */
    fun assertApiMatches(api: Class<*>) {
        val operations = api.declaredMethods.mapNotNull(::operationOf)
        assertTrue("${api.simpleName} no declara operaciones", operations.isNotEmpty())
        operations.forEach { (method, path) ->
            assertTrue("${api.simpleName}: $method $path no existe en el contrato $version", hasOperation(method, path))
        }
    }

    private fun operationOf(method: Method): Pair<String, String>? {
        method.getAnnotation(GET::class.java)?.let { return "get" to "/" + it.value }
        method.getAnnotation(POST::class.java)?.let { return "post" to "/" + it.value }
        return null
    }

    private fun schema(name: String): JsonObject =
        schemas[name]?.jsonObject ?: fail("El contrato $version no define el esquema $name") as Nothing

    private fun operation(method: String, path: String): JsonObject =
        paths[path]?.jsonObject?.get(method)?.jsonObject
            ?: fail("El contrato $version no define $method $path") as Nothing

    companion object {
        const val RESOURCE = "/contracts/bff-mobile.openapi.json"

        fun load(): OpenApiContract {
            val text = OpenApiContract::class.java.getResourceAsStream(RESOURCE)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("No se encontró $RESOURCE en los recursos de prueba")
            return OpenApiContract(Json.parseToJsonElement(text).jsonObject)
        }

        fun assertSameElements(message: String, expected: Set<String>, actual: Set<String>) =
            assertEquals(message, expected.sorted(), actual.sorted())
    }
}
