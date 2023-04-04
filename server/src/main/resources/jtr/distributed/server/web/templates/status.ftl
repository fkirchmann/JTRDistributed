<#import "util/components.ftl" as components>

<@components.header title="Status">
    <#if !(passwordFound??)>
        <meta http-equiv="refresh" content="2" >
    </#if>
</@components.header>

<div class="container">

    <#if passwordFound??>
        <div class="alert alert-success" role="alert">
            Password Found: "${passwordFound}"
        </div>
    </#if>

    <h2>Progress: ${progressPercent?string["0.000"]} %<#if (hashrate > 0)>,
        ${timeRemaining} remaining @ ${(hashrate/1000000)?string["0.0"]} MP/s</#if></h2>
    <div class="progress" style="height: 30px;">
        <div class="progress-bar" role="progressbar"
             style="width: ${progressPercent?string["0.00"]}%; font-size: 12pt;">
        </div>
    </div>
    <br>
    <h2>Clients</h2>
    <table class="table table-sm table-striped">
        <thead>
            <tr>
                <th scope="col" style="width: 20%">Client</th>
                <th scope="col" style="width: 20%">GPU</th>
                <th scope="col" style="width: 10%">Hashrate</th>
                <th scope="col" style="width: 10%">Measured</th>
                <th scope="col" style="width: 10%">Progress</th>
                <th scope="col" style="width: 40%">Last Guessed Password</th>
            </tr>
        </thead>
        <tbody>
            <#list clients as client>
                <#if assignments?api.getAssignment(client.clientId)??>
                    <#assign assignment = assignments?api.getAssignment(client.clientId)>
                </#if>
                <tr>
                    <th scope="row">${client.clientId}</th>
                    <td>${client.gpuModel!"Unknown"}</td>
                    <td>${(client.lastGuessesPerSecond / 1000)?string["0"]} kP/s</td>
                    <td><#if averageHashrates[client.clientId]??>
                        ${(averageHashrates[client.clientId] / 1000)?string["0"]} kP/s
                    </#if></td>
                    <td><#if assignment??>
                            ${(assignments?api.getAssignmentCompletion(assignment) * 100.0)?string["0.00"]} %
                    </#if></td>
                    <td><#if client.lastGuessedPassword??>"${client.lastGuessedPassword}"</#if></td>
                </tr>
            </#list>
        </tbody>
    </table>
</div>

<@components.footer />