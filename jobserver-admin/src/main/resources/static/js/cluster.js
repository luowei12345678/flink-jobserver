var Cluster = function () {
    let winWidth, winHeight;
    let table = layui.table;
    let form = layui.form;
    let dropdown = layui.dropdown;
    const maxInstanceCount = $("#maxInstanceCount").val();

    let jobserverEditor, flinkEditor, coreEditor, hdfsEditor, yarnEditor, hiveEditor;

    return {
        init: function () {
            winWidth = $(window).width() * 0.9;
            winHeight = $(window).height() * 0.9;

            let cols = [
                [{
                    title: '序号',
                    type: 'numbers'
                },
                    {
                        title: '集群Code',
                        field: 'code',
                        align: 'left',
                        width: 100
                    },
                    {
                        title: '集群名称',
                        field: 'name',
                        align: 'left',
                        width: 100,
                    },
                    {
                        title: '调度类型',
                        field: 'schedulerType',
                        align: 'left',
                        width: 100,
                    },
                    {
                        title: '开启kerberos',
                        field: 'kerberosEnabled',
                        align: 'left',
                        width: 100,
                        templet: function(record) {
                            const status = record.status;
                            if (status === 1) {
                                return '<span style="font-weight:bold; color: #5FB878">启用</span>'
                            } else {
                                return '<span style="font-weight:bold;color: #FF5722">关闭</span>'
                            }
                        }
                    },
                    {
                        title: '状态',
                        field: 'status',
                        align: 'left',
                        width: 80,
                        templet: function(record) {
                            const status = record.status;
                            if (status === 1) {
                                return '<span style="font-weight:bold; color: #5FB878">启用</span>'
                            } else {
                                return '<span style="font-weight:bold;color: #FF5722">关闭</span>'
                            }
                        }
                    },
                    {
                        title: '更新时间',
                        field: 'gmtModified',
                        align: 'left',
                        width: 150
                    },
                    {
                        title: '操作',
                        toolbar: '#cluster-bar',
                        align: 'right',
                        width: 50,
                        fixed: "right"
                    }
                ]
            ]

            table.render({
                elem: '#cluster-table',
                url: '/cluster/queryClusters',
                page: true,
                cols: cols,
                skin: 'line',
                parseData: function (res) {
                    return {
                        "code": 0,
                        "count": res.total,
                        "data": res.rows
                    };
                },
                toolbar: '#toolbarDemo',
                defaultToolbar: [],
                done: function(res, curr, count) {
                    for (var i = 0; i < res.data.length; i++) {
                        const row = res.data[i];
                        const menus = []
                        menus.push({title: '编辑集群', id: "editorCluster", clusterId: row.id, clusterCode: row.code});
                        menus.push({title: '关闭集群', id: "closeCluster", clusterId: row.id, clusterCode: row.code});

                        dropdown.render({
                            elem: '#opt_' + row.id,
                            data: menus,
                            id: "#opt_" + row.id,
                            click: function(obj) {
                                if (obj.id === "editorCluster") {
                                    Cluster.newClusterWin(obj.clusterId)
                                } else if (obj.id === "closeCluster") {
                                    Cluster.closeCluster(obj.clusterId, obj.clusterCode)
                                }
                            }
                        });
                    }
                }
            });

            table.on('toolbar(cluster-table)', function(obj) {
                if (obj.event === 'refresh') {
                    Cluster.refresh();
                }
            });

            form.on('submit(user-query)', function(data) {
                table.reload('cluster-table', {
                    where: data.field
                })
                return false;
            });

            jobserverEditor = Cluster.getEditor(jobserverEditor, "jobserverEditor", "ace/mode/properties");
            flinkEditor = Cluster.getEditor(flinkEditor, "flinkEditor", "ace/mode/yaml");
            coreEditor = Cluster.getEditor(coreEditor, "coreEditor", "ace/mode/xml");
            hdfsEditor = Cluster.getEditor(hdfsEditor, "hdfsEditor", "ace/mode/xml");
            yarnEditor = Cluster.getEditor(yarnEditor, "yarnEditor", "ace/mode/xml");
            hiveEditor = Cluster.getEditor(hiveEditor, "hiveEditor", "ace/mode/xml");
            //let kerberosEditor = Cluster.getEditor(kerberosEditor, "kerberosEditor", "ace/mode/properties");
        },

        getEditor: function(editor, editorId, mode) {
            editor = ace.edit(editorId);
            editor.setTheme("ace/theme/cobalt");
            editor.getSession().setMode(mode);
            $('#' + editorId).height((winHeight - 285) + "px");
            editor.resize();
            return editor;
        },

        setEditorValue : function(editor, config) {
            if (config == null) {
                editor.setValue("");
            } else {
                editor.setValue(config);
            }
            editor.clearSelection();
        },

        newClusterWin : function(clusterId) {
            if (clusterId) {
                $.ajax({
                    async: true,
                    type : "GET",
                    url: '/cluster/queryCluster',
                    data: { clusterId: clusterId },
                    success: function (result) {
                        if (result.success) {
                            let data = result.data;
                            form.val('newClusterForm', data);
                            Cluster.setEditorValue(jobserverEditor, data.jobserverConfig)
                            Cluster.setEditorValue(flinkEditor, data.flinkConfig)
                            Cluster.setEditorValue(coreEditor, data.coreConfig)
                            Cluster.setEditorValue(hdfsEditor, data.hdfsConfig)
                            Cluster.setEditorValue(yarnEditor, data.yarnConfig)
                            Cluster.setEditorValue(hiveEditor, data.hiveConfig)
                        }
                    }
                })
            } else {
                form.val('newClusterForm', {code: "", name: "", yarnQueueName: "default"});
                Cluster.setEditorValue(jobserverEditor, $("#confDefaultValue").val())
                Cluster.setEditorValue(flinkEditor, "")
                Cluster.setEditorValue(coreEditor, "")
                Cluster.setEditorValue(hdfsEditor, "")
                Cluster.setEditorValue(yarnEditor, "")
                Cluster.setEditorValue(hiveEditor, "")
            }

            var index = layer.open({
                type: 1,
                title: '新建集群',
                area: [winWidth + 'px', winHeight + "px"],
                shade: 0, //去掉遮罩
                resize: false,
                btnAlign: 'c',
                content: $("#newClusterDiv"),
                btn: ['保存'],
                btn1: function(index, layero) {
                    let data = form.val('newClusterForm');
                    if (!data.code) {
                        toastr.error("集群code不能为空");
                        return
                    }
                    if (!data.name) {
                        toastr.error("集群名称不能为空");
                        return
                    }

                    let jobserverConfig = $.trim(jobserverEditor.getValue());
                    let flinkConfig = $.trim(flinkEditor.getValue());
                    let coreConfig = $.trim(coreEditor.getValue());
                    let hdfsConfig = $.trim(hdfsEditor.getValue());
                    let yarnConfig = $.trim(yarnEditor.getValue());
                    let hiveConfig = $.trim(hiveEditor.getValue());

                    data.id = clusterId
                    data.jobserverConfig = jobserverConfig
                    data.flinkConfig = flinkConfig
                    data.coreConfig = coreConfig
                    data.hdfsConfig = hdfsConfig
                    data.yarnConfig = yarnConfig
                    data.hiveConfig = hiveConfig
                    $.ajax({
                        async: true,
                        type: "POST",
                        url: '/cluster/saveCluster',
                        data: data,
                        success: function (result) {
                            if (result.success) {
                                toastr.success("保存成功");
                                Cluster.refresh();
                            } else {
                                toastr.error(result.message);
                            }
                        }
                    });
                }
            });
        },

        closeCluster : function (clusterId, clusterCode) {
            layer.confirm('确定关闭: ' + clusterCode + " ?", {
                btn: ['确认','取消'],
                title: '提示'
            }, function (index) {
                layer.close(index);
                $.ajax({
                    async: true,
                    type : "POST",
                    url: '/cluster/closeCluster',
                    data: { clusterId: clusterId },
                    success: function (result) {
                        if (result.success) {
                            toastr.success("成功关闭集群: " + clusterCode)
                            table.reload('cluster-table');
                        } else {
                            toastr.error(result.message)
                        }
                    }
                })
            })
        },

        refresh : function() {
            table.reload('cluster-table');
        }
    };
}();

$(document).ready(function () {
    Cluster.init();
});
